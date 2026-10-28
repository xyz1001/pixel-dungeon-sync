package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.GameSourceIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** PM is the only source of target paths. This class never prepares, signs or installs APKs. */
public final class RootTargetResolver {
    private final Context context;
    public RootTargetResolver(Context context) { this.context=context.getApplicationContext(); }

    public SelectedRootGame selectInstalled(String packageName) throws Exception {
        SelectedRootGame before=snapshot(packageName);
        SelectedRootGame after=snapshot(packageName);
        if(!before.matches(after)) throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_01.text());
        return after;
    }
    public SelectedRootGame requireUnchanged(SelectedRootGame selected) throws Exception {
        if(selected==null || !selected.matches(snapshot(selected.gamePackage)))
            throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
        return selected;
    }
    /** One export only. Root independently hashes/binds the package at its RPC boundary. */
    public AutoCloseable exportScope(SelectedRootGame selected)throws Exception {
        String before=installationStamp(selected);
        pdsync.DiagnosticTiming.measure(pdsync.DiagnosticPhase.ROOT_CLIENT_IDENTITY,()->{requireUnchanged(selected);return null;});
        if(!before.equals(installationStamp(selected)))throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
        return ()->{if(!before.equals(installationStamp(selected)))throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());};
    }
    private String installationStamp(SelectedRootGame selected)throws Exception {
        return pdsync.DiagnosticTiming.measure(pdsync.DiagnosticPhase.ROOT_CLIENT_METADATA,()->installationStampValue(selected));
    }
    private String installationStampValue(SelectedRootGame selected)throws Exception {
        PackageManager pm=context.getPackageManager();
        PackageInfo info=pm.getPackageInfo(selected.gamePackage,PackageTrust.signingFlags());
        if(info.applicationInfo==null || !info.applicationInfo.enabled || !selected.gamePackage.equals(info.packageName)
                || !selected.gamePackage.equals(info.applicationInfo.packageName) || info.applicationInfo.uid!=selected.uid
                || !selected.dataDir.equals(info.applicationInfo.dataDir) || Process.myUid()/100000!=selected.userId)
            throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
        String[] members=pm.getPackagesForUid(selected.uid);
        if(members==null || !Arrays.asList(members).contains(selected.gamePackage))throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
        List<String> parts=new ArrayList<>();
        parts.add(Long.toString(PackageTrust.versionCode(info)));parts.add(Long.toString(info.firstInstallTime));parts.add(Long.toString(info.lastUpdateTime));
        List<String> signers=new ArrayList<>();
        for(Signature signer:PackageTrust.currentSigners(info))signers.add(PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())));
        Collections.sort(signers);parts.addAll(signers);
        Map<String,String> files=new TreeMap<>();
        for(File apk:PackageTrust.installedApks(info)) {
            if(!apk.isFile() || !apk.getAbsoluteFile().equals(apk.getCanonicalFile()))throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
            files.put(apk.getPath(),apk.length()+":"+apk.lastModified());
        }
        if(!files.keySet().equals(selected.apkHashes.keySet()))throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_02.text());
        parts.add(files.toString());return parts.toString();
    }
    private SelectedRootGame snapshot(String packageName) throws Exception {
        if(packageName==null || packageName.equals(context.getPackageName())) throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_03.text());
        PackageManager pm=context.getPackageManager();
        PackageInfo info=pm.getPackageInfo(packageName,PackageTrust.signingFlags()|PackageManager.GET_META_DATA|PackageManager.GET_PROVIDERS);
        if(info.applicationInfo==null || !info.applicationInfo.enabled || info.applicationInfo.dataDir==null
                || !packageName.equals(info.packageName) || !packageName.equals(info.applicationInfo.packageName))
            throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_04.text());
        int uid=info.applicationInfo.uid, user=Process.myUid()/100000;
        if(uid/100000!=user || uid==Process.myUid()) throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_05.text());
        String[] packages=pm.getPackagesForUid(uid);
        if(packages==null || !Arrays.asList(packages).contains(packageName))
            throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_06.text());
        // A shared UID grants no sibling paths: every path below comes from this package's PM record.
        // Runtime quiescence is UID-wide, so an active/unknown shared peer still blocks file operations.
        List<File> apks=PackageTrust.installedApks(info);
        if(apks.size()>64)throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_07.text());
        Map<String,String> hashes=new TreeMap<>();
        long total=0;
        for(File apk:apks) {
            total+=apk.length();
            if(total>8589934592L || !apk.isFile() || !apk.getAbsoluteFile().equals(apk.getCanonicalFile())
                    || hashes.put(apk.getPath(),PackageTrust.sha256(apk))!=null)
                throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_08.text());
        }
        List<String> current=new ArrayList<>();
        for(Signature signature:PackageTrust.currentSigners(info))
            current.add(PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        Collections.sort(current);
        GameSourceIdentity source = new GameSourceIdentity(packageName, current);
        String sourceSet=sourceSet(info,hashes);
        boolean retainedProvenance=false;
        // Root reads the installed package as-is. Preparation/key availability is optional provenance,
        // never a prerequisite for file access. Only a validated exact receipt can name another signer.
        try {
            GamePreparationService preparation=new GamePreparationService(context);
            GamePreparationService.PreparedPair retained=preparation.reloadPrepared();
            if(retained!=null && packageName.equals(retained.gamePackage)
                    && retained.gameVersionCode==PackageTrust.versionCode(info)
                    && current.equals(Collections.singletonList(retained.signingCertificateSha256))
                    && PackageTrust.apkSetSha256(retained.gameApks).equals(ApkSetIdentity.digest(new ArrayList<>(hashes.values())))) {
                preparation.validatePrepared(retained);
                source=GameSourceIdentity.restoreProvenance(packageName, retained.sourceSignerCertificateSha256,
                        retained.sourceProvenance, retained.originIdentity); sourceSet=retained.sourceApkSetSha256;
                retainedProvenance=true;
            }
        } catch(Exception unavailableProvenance) {
            // Keep existing receipts untouched. The actual signer gets its own source namespace;
            // unverified manifest metadata must never impersonate the original publisher.
        }
        if (!retainedProvenance && hasOwnBridgeMarkers(info)) {
            // An exact recognized embedded runtime can preserve its source namespace even if the
            // old receipt was lost. This is inherited provenance, never an invented publisher signer.
            InstalledBridgeBinding embedded = null;
            try { embedded = InstalledBridgeResolver.resolve(context, packageName); }
            catch (Exception unrecognizedBridge) { /* Root does not depend on an embedded bridge being supported. */ }
            if (embedded != null && packageName.equals(embedded.gamePackage) && uid == embedded.uid
                    && PackageTrust.versionCode(info) == embedded.gameVersionCode
                    && info.firstInstallTime == embedded.firstInstallTime && info.lastUpdateTime == embedded.lastUpdateTime
                    && info.applicationInfo.dataDir.equals(embedded.dataDir)
                    && current.equals(Collections.singletonList(embedded.signingCertificateSha256))
                    && hashes.keySet().equals(new java.util.HashSet<>(embedded.apkPaths))) {
                source = GameSourceIdentity.restoreProvenance(packageName, Collections.emptyList(),
                        GameSourceIdentity.Provenance.INHERITED_EMBEDDED, embedded.originIdentity);
                sourceSet = embedded.sourceApkSetSha256; retainedProvenance = true;
            }
        }
        String version=info.versionName==null || info.versionName.trim().isEmpty()?Long.toString(PackageTrust.versionCode(info)):info.versionName;
        return new SelectedRootGame(packageName,pm.getApplicationLabel(info.applicationInfo).toString(),version,
                PackageTrust.versionCode(info),info.firstInstallTime,info.lastUpdateTime,user,uid,info.applicationInfo.dataDir,
                current,source,sourceSet,hashes,retainedProvenance);
    }
    private static boolean hasOwnBridgeMarkers(PackageInfo info) {
        if (info.applicationInfo.metaData != null
                && info.applicationInfo.metaData.containsKey("io.github.xyz1001.pdsync.BRIDGE_MODE")) return true;
        if (info.providers != null) for (android.content.pm.ProviderInfo provider : info.providers)
            if (provider != null && "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(provider.name)) return true;
        return false;
    }
    private static String sourceSet(PackageInfo info,Map<String,String> hashes)throws Exception {
        TreeMap<String,String> bySplit=new TreeMap<>();bySplit.put("",hashes.get(info.applicationInfo.sourceDir));
        String[] paths=info.applicationInfo.splitSourceDirs;
        if(paths!=null) {
            if(info.splitNames==null || info.splitNames.length!=paths.length)throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_09.text());
            for(int i=0;i<paths.length;i++)if(info.splitNames[i]==null || info.splitNames[i].isEmpty()
                    || bySplit.put(info.splitNames[i],hashes.get(paths[i]))!=null)throw new IOException(AndroidMessages.ROOT_TARGET_RESOLVER_10.text());
        }
        StringBuilder bytes=new StringBuilder("pdsync-apk-set-v1\n");
        for(Map.Entry<String,String> entry:bySplit.entrySet())bytes.append(entry.getKey().length()).append(':')
                .append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        return PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(bytes.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
