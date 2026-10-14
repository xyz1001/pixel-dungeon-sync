"""Strict non-secret JSON configuration; validation never opens a network socket."""
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import re
import stat
from urllib.parse import urlsplit


PREREQUISITES = (
    'all_managed_aliases_gated', 'direct_dav_closed', 'management_api_private',
    'no_delete_or_reuse_managed_usernames', 'fixed_drop_isolated',
    'other_write_protocols_closed',
)
MAX_CONFIG_BYTES = 32768


class ConfigurationError(ValueError):
    pass


def strict_json(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ConfigurationError('DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ConfigurationError('INVALID_JSON_NUMBER')))


def _object(value, required, optional=()):
    if type(value) is not dict or not set(required) <= set(value) or set(value) - set(required) - set(optional):
        raise ConfigurationError('INVALID_CONFIGURATION_FIELDS')
    return value


def _bool(value):
    if type(value) is not bool:
        raise ConfigurationError('BOOLEAN_REQUIRED')
    return value


def _integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ConfigurationError('INTEGER_OUT_OF_RANGE')
    return value


def _seconds(value, minimum, maximum):
    if type(value) not in (int, float) or not math.isfinite(value) or not minimum <= value <= maximum:
        raise ConfigurationError('TIMEOUT_OUT_OF_RANGE')
    return value


def _path(value):
    if (type(value) is not str or not value.startswith('/') or value.startswith('//')
            or value == '/' or str(Path(value)) != value or '..' in Path(value).parts
            or any(ord(c) < 32 or ord(c) == 127 for c in value)):
        raise ConfigurationError('CANONICAL_ABSOLUTE_PATH_REQUIRED')
    return value


def _environment(value):
    if type(value) is not str or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,127}', value):
        raise ConfigurationError('EXPLICIT_ENVIRONMENT_NAME_REQUIRED')
    return value


def _secret_source(value, admin=False):
    if type(value) is not dict:
        raise ConfigurationError('EXPLICIT_SECRET_SOURCE_REQUIRED')
    if set(value) == {'file'}:
        _path(value['file'])
    elif admin and set(value) == {'username_env', 'password_env'}:
        _environment(value['username_env'])
        _environment(value['password_env'])
        if value['username_env'] == value['password_env']:
            raise ConfigurationError('DISTINCT_ADMIN_ENVIRONMENT_NAMES_REQUIRED')
    elif not admin and set(value) == {'env'}:
        _environment(value['env'])
    else:
        raise ConfigurationError('EXPLICIT_SECRET_SOURCE_REQUIRED')


def _url(value, *, public=False, allow_loopback_http=False):
    try:
        parts = urlsplit(value)
        if (type(value) is not str or any(ord(c) <= 32 or ord(c) == 127 for c in value)
                or not parts.hostname or parts.username is not None or parts.password is not None
                or parts.query or parts.fragment or (parts.port is not None and not 1 <= parts.port <= 65535)
                or (not public and parts.path not in ('', '/'))):
            raise ValueError()
        if parts.scheme != 'https' and not (not public and allow_loopback_http
                and parts.scheme == 'http' and parts.hostname in ('127.0.0.1', '::1')):
            raise ValueError()
    except (TypeError, ValueError, AttributeError):
        raise ConfigurationError('INVALID_SERVICE_URL') from None


@dataclass(frozen=True)
class RuntimeConfig:
    enabled: bool
    mutations_enabled: bool
    listen: dict
    database_path: str
    public_webdav_url: str
    sftpgo: dict
    prerequisites: dict
    feishu: dict

    @classmethod
    def parse(cls, value):
        value = _object(value, ('version', 'listen', 'database_path', 'public_webdav_url', 'sftpgo', 'prerequisites', 'feishu'),
                        ('enabled', 'mutations_enabled'))
        if type(value['version']) is not int or value['version'] != 1:
            raise ConfigurationError('CONFIG_VERSION_UNSUPPORTED')
        enabled, mutations = _bool(value.get('enabled', False)), _bool(value.get('mutations_enabled', False))
        listen = _object(value['listen'], ('host', 'port', 'max_workers', 'request_timeout_seconds'))
        if listen['host'] != '127.0.0.1':
            raise ConfigurationError('LITERAL_LOOPBACK_LISTENER_REQUIRED')
        _integer(listen['port'], 1, 65535)
        _integer(listen['max_workers'], 1, 64)
        _seconds(listen['request_timeout_seconds'], 1, 60)
        _path(value['database_path'])
        _url(value['public_webdav_url'], public=True)
        sftpgo = _object(value['sftpgo'], ('base_url', 'instance_id', 'admin_credentials', 'allow_loopback_http',
                                          'timeout_seconds', 'new_account'))
        _bool(sftpgo['allow_loopback_http'])
        _url(sftpgo['base_url'], allow_loopback_http=sftpgo['allow_loopback_http'])
        if type(sftpgo['instance_id']) is not str or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', sftpgo['instance_id']):
            raise ConfigurationError('STABLE_INSTANCE_ID_REQUIRED')
        if enabled and 'REPLACE' in sftpgo['instance_id'].upper():
            raise ConfigurationError('STABLE_INSTANCE_ID_REQUIRED')
        _secret_source(sftpgo['admin_credentials'], admin=True)
        _seconds(sftpgo['timeout_seconds'], 0.1, 15)
        template = _object(sftpgo['new_account'], ('home_root', 'quota_bytes', 'upload_bandwidth_kb', 'download_bandwidth_kb'))
        _path(template['home_root'])
        for key in ('quota_bytes', 'upload_bandwidth_kb', 'download_bandwidth_kb'):
            _integer(template[key], 1, 2**63 - 1)
        prerequisites = _object(value['prerequisites'], PREREQUISITES)
        for acknowledged in prerequisites.values():
            _bool(acknowledged)
        if enabled and not prerequisites['no_delete_or_reuse_managed_usernames']:
            raise ConfigurationError('ACCOUNT_LIFECYCLE_ATTESTATION_REQUIRED')
        if mutations and (not enabled or not all(prerequisites.values())):
            raise ConfigurationError('MUTATION_READINESS_ATTESTATIONS_REQUIRED')
        feishu = _object(value['feishu'], ('enabled',), ('binding_file', 'app_secret', 'queue_path'))
        _bool(feishu['enabled'])
        for key in ('binding_file', 'queue_path'):
            if key in feishu:
                _path(feishu[key])
        if 'app_secret' in feishu:
            _secret_source(feishu['app_secret'])
        if feishu['enabled']:
            if not mutations or not {'binding_file', 'app_secret', 'queue_path'} <= set(feishu):
                raise ConfigurationError('FEISHU_REQUIRES_MUTATIONS_AND_COMPLETE_CONFIGURATION')
            if feishu['queue_path'] == value['database_path']:
                raise ConfigurationError('SEPARATE_FEISHU_QUEUE_PATH_REQUIRED')
        return cls(enabled, mutations, dict(listen), value['database_path'], value['public_webdav_url'],
                   dict(sftpgo), dict(prerequisites), dict(feishu))

    @classmethod
    def load(cls, path):
        try:
            with open(path, 'rb') as handle:
                raw = handle.read(MAX_CONFIG_BYTES + 1)
            if len(raw) > MAX_CONFIG_BYTES:
                raise ConfigurationError('CONFIGURATION_TOO_LARGE')
            return cls.parse(strict_json(raw.decode('utf-8')))
        except ConfigurationError:
            raise
        except (OSError, ValueError, UnicodeError, RecursionError):
            raise ConfigurationError('CONFIGURATION_UNREADABLE_OR_INVALID') from None


def load_runtime_secret(source):
    """Called by serve only, after explicit external configuration is supplied."""
    try:
        if 'env' in source:
            secret = os.environ[source['env']]
        else:
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            with os.fdopen(os.open(source['file'], flags), 'rb') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077 or info.st_size > 4096:
                    raise ValueError()
                secret = stream.read(4097).decode('utf-8').removesuffix('\n')
        if (type(secret) is not str or not 1 <= len(secret.encode('utf-8')) <= 4096
                or any(ord(c) <= 32 or ord(c) == 127 for c in secret) or 'REPLACE' in secret.upper()):
            raise ValueError()
        return secret
    except (OSError, ValueError, KeyError, TypeError, UnicodeError):
        raise ConfigurationError('FEISHU_APP_SECRET_UNAVAILABLE') from None
