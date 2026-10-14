"""Strict non-secret JSON configuration; validation never opens a network socket."""
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import struct
from urllib.parse import urlsplit
import zlib


PREREQUISITES = (
    'all_managed_aliases_gated', 'direct_dav_closed', 'management_api_private',
    'no_delete_or_reuse_managed_usernames', 'fixed_drop_isolated',
    'other_write_protocols_closed',
)
MAX_CONFIG_BYTES = 32768
MAX_APPRECIATION_BYTES = 1024 * 1024
MAX_APPRECIATION_DIMENSION = 2048
MAX_APPRECIATION_PIXELS = 2_000_000


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
    appreciation: dict = field(default_factory=lambda: {'enabled': False})
    account_expiry: dict = field(default_factory=lambda: {'enabled': False, 'single_writer': False})

    @classmethod
    def parse(cls, value):
        value = _object(value, ('version', 'listen', 'database_path', 'public_webdav_url', 'sftpgo', 'prerequisites', 'feishu'),
                        ('enabled', 'mutations_enabled', 'appreciation', 'account_expiry'))
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
        account_expiry = _object(value.get('account_expiry', {'enabled': False, 'single_writer': False}),
                                 ('enabled', 'single_writer'))
        _bool(account_expiry['enabled'])
        _bool(account_expiry['single_writer'])
        if account_expiry['enabled'] and (not enabled or not mutations or not account_expiry['single_writer']):
            raise ConfigurationError('NATIVE_EXPIRY_REQUIRES_ENABLED_SINGLE_WRITER')
        prerequisites = _object(value['prerequisites'], PREREQUISITES)
        for acknowledged in prerequisites.values():
            _bool(acknowledged)
        if enabled and not prerequisites['no_delete_or_reuse_managed_usernames']:
            raise ConfigurationError('ACCOUNT_LIFECYCLE_ATTESTATION_REQUIRED')
        required = [value for key, value in prerequisites.items()
                    if key != 'all_managed_aliases_gated' or not account_expiry['enabled']]
        if mutations and (not enabled or not all(required)):
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
        appreciation = _object(value.get('appreciation', {'enabled': False}), ('enabled',),
                               ('asset_path', 'expected_sha256'))
        _bool(appreciation['enabled'])
        if 'asset_path' in appreciation:
            _path(appreciation['asset_path'])
        if 'expected_sha256' in appreciation and (type(appreciation['expected_sha256']) is not str
                or not re.fullmatch(r'[0-9a-f]{64}', appreciation['expected_sha256'])):
            raise ConfigurationError('APPRECIATION_SHA256_REQUIRED')
        if appreciation['enabled'] and not {'asset_path', 'expected_sha256'} <= set(appreciation):
            raise ConfigurationError('APPRECIATION_ASSET_CONFIGURATION_REQUIRED')
        return cls(enabled, mutations, dict(listen), value['database_path'], value['public_webdav_url'],
                   dict(sftpgo), dict(prerequisites), dict(feishu), dict(appreciation), dict(account_expiry))

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


@dataclass(frozen=True)
class AppreciationAsset:
    png: bytes = field(repr=False)
    sha256: str


def _validate_appreciation_png(raw):
    """Bounded PNG structure, CRC, IHDR and decoded scanline validation.

    This is not a payment/QR verifier or a visual review of the image.
    """
    if not raw.startswith(b'\x89PNG\r\n\x1a\n'):
        raise ConfigurationError('APPRECIATION_PNG_INVALID')
    offset, chunks, image_data = 8, [], bytearray()
    width = height = depth = color = interlace = None
    while offset < len(raw):
        if offset + 12 > len(raw):
            raise ConfigurationError('APPRECIATION_PNG_INVALID')
        size = struct.unpack_from('>I', raw, offset)[0]
        kind = raw[offset + 4:offset + 8]
        end = offset + 12 + size
        if (end > len(raw) or not re.fullmatch(b'[A-Za-z]{4}', kind)
                or kind[2] & 32 or zlib.crc32(raw[offset + 4:end - 4]) != struct.unpack_from('>I', raw, end - 4)[0]):
            raise ConfigurationError('APPRECIATION_PNG_INVALID')
        data = raw[offset + 8:end - 4]
        if not chunks and kind != b'IHDR':
            raise ConfigurationError('APPRECIATION_PNG_INVALID')
        if kind == b'IHDR':
            if chunks or size != 13:
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', data)
            if (not 1 <= width <= MAX_APPRECIATION_DIMENSION or not 1 <= height <= MAX_APPRECIATION_DIMENSION
                    or width * height > MAX_APPRECIATION_PIXELS):
                raise ConfigurationError('APPRECIATION_PNG_DIMENSIONS_INVALID')
            valid_depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            if depth not in valid_depths.get(color, ()) or compression or filtering or interlace not in (0, 1):
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
        elif kind == b'PLTE':
            if b'PLTE' in chunks or b'IDAT' in chunks or color in (0, 4) or not size or size % 3 or size > 768:
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
            if color == 3 and size // 3 > 2**depth:
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
        elif kind == b'IDAT':
            if (b'IDAT' in chunks and chunks[-1] != b'IDAT') or (color == 3 and b'PLTE' not in chunks):
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
            image_data.extend(data)
        elif kind == b'IEND':
            if size or b'IDAT' not in chunks or end != len(raw):
                raise ConfigurationError('APPRECIATION_PNG_INVALID')
        elif not kind[0] & 32 or kind in (b'acTL', b'fcTL', b'fdAT'):
            # A fixed still PNG only; reject unknown critical chunks and APNG.
            raise ConfigurationError('APPRECIATION_PNG_INVALID')
        chunks.append(kind)
        offset = end
    if not chunks or chunks[-1] != b'IEND':
        raise ConfigurationError('APPRECIATION_PNG_INVALID')
    samples = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    passes = ((0, 0, 1, 1),) if not interlace else (
        (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
        (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2))
    rows = []
    for x, y, dx, dy in passes:
        columns, count = max(0, (width - x + dx - 1) // dx), max(0, (height - y + dy - 1) // dy)
        if columns and count:
            rows.extend([1 + (columns * samples * depth + 7) // 8] * count)
    expected = sum(rows)
    try:
        decoder = zlib.decompressobj()
        pixels = decoder.decompress(image_data, expected + 1)
        if len(pixels) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise ValueError()
        offset = 0
        for row in rows:
            if pixels[offset] > 4:
                raise ValueError()
            offset += row
    except (ValueError, zlib.error):
        raise ConfigurationError('APPRECIATION_PNG_INVALID') from None


def load_appreciation_asset(settings):
    """Load once before listening; requests never open a caller-selected file.

    Disabled means no file is opened. Every path component must be nonsymlink;
    the final descriptor must be a regular file. Reading is size-bounded even if
    the file changes. Only immutable, hash-matched bytes are retained in memory.
    """
    if not settings['enabled']:
        return None
    try:
        parts = Path(_path(settings['asset_path'])).parts
        directory = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
        try:
            for part in parts[1:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory)
                directory = child
            descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        finally:
            os.close(directory)
        try:
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode):
                raise ConfigurationError('APPRECIATION_ASSET_REGULAR_FILE_REQUIRED')
            if not 1 <= info.st_size <= MAX_APPRECIATION_BYTES:
                raise ConfigurationError('APPRECIATION_ASSET_SIZE_INVALID')
            raw = bytearray()
            while len(raw) <= MAX_APPRECIATION_BYTES:
                piece = os.read(descriptor, min(65536, MAX_APPRECIATION_BYTES + 1 - len(raw)))
                if not piece:
                    break
                raw.extend(piece)
        finally:
            os.close(descriptor)
        raw = bytes(raw)
        if not 1 <= len(raw) <= MAX_APPRECIATION_BYTES:
            raise ConfigurationError('APPRECIATION_ASSET_SIZE_INVALID')
        digest = hashlib.sha256(raw).hexdigest()
        if digest != settings['expected_sha256']:
            raise ConfigurationError('APPRECIATION_ASSET_HASH_MISMATCH')
        _validate_appreciation_png(raw)
        return AppreciationAsset(raw, digest)
    except ConfigurationError:
        raise
    except (OSError, ValueError, KeyError, TypeError):
        raise ConfigurationError('APPRECIATION_ASSET_UNAVAILABLE') from None


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
