package io.github.xyz1001.pdsync.root;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** In-memory, one-job PM/APK metadata fence; never substitutes for initial/final full hashes. */
public final class RootInstalledStamp {
    private final String bindingFingerprint;
    private final long version, installedAt, updatedAt;
    private final Map<String,String> apkMetadata;
    public RootInstalledStamp(RootBinding binding,long version,long installedAt,long updatedAt,
            Map<String,String> apkMetadata) throws IOException {
        if(binding==null || version<0 || installedAt<0 || updatedAt<0 || apkMetadata==null
                || !binding.apkHashes.keySet().equals(apkMetadata.keySet()))throw new IOException("Invalid installed metadata fence");
        TreeMap<String,String> copy=new TreeMap<>(apkMetadata);
        for(String value:copy.values())if(value==null || value.isEmpty() || value.length()>512)
            throw new IOException("Invalid APK metadata identity");
        this.bindingFingerprint=binding.fingerprint();this.version=version;this.installedAt=installedAt;
        this.updatedAt=updatedAt;this.apkMetadata=Collections.unmodifiableMap(copy);
    }
    public boolean same(RootInstalledStamp other) {
        return other!=null && bindingFingerprint.equals(other.bindingFingerprint) && version==other.version
                && installedAt==other.installedAt && updatedAt==other.updatedAt && apkMetadata.equals(other.apkMetadata);
    }
    public boolean binds(RootBinding binding)throws IOException{return binding!=null&&bindingFingerprint.equals(binding.fingerprint());}
}
