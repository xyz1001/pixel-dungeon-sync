package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
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
        if(!before.matches(after)) throw new IOException("所选游戏在读取身份期间发生变化，请重新选择。");
        return after;
    }
    public SelectedRootGame requireUnchanged(SelectedRootGame selected) throws Exception {
        if(selected==null || !selected.matches(snapshot(selected.gamePackage)))
            throw new IOException("Root 所选游戏、签名或完整安装内容已变化，请重新选择并授权。");
        return selected;
    }
    private SelectedRootGame snapshot(String packageName) throws Exception {
        if(packageName==null || packageName.equals(context.getPackageName())) throw new IOException("请选择已安装的游戏。");
        PackageManager pm=context.getPackageManager();
        PackageInfo info=pm.getPackageInfo(packageName,PackageTrust.signingFlags()|PackageManager.GET_META_DATA|PackageManager.GET_PROVIDERS);
        if(info.applicationInfo==null || !info.applicationInfo.enabled || info.applicationInfo.dataDir==null
                || !packageName.equals(info.packageName) || !packageName.equals(info.applicationInfo.packageName))
            throw new IOException("所选游戏安装信息不可用。");
        int uid=info.applicationInfo.uid, user=Process.myUid()/100000;
        if(uid/100000!=user || uid==Process.myUid()) throw new IOException("Root 只支持当前用户且与主应用 UID 不同的游戏。");
        String[] packages=pm.getPackagesForUid(uid);
        if(packages==null || !Arrays.asList(packages).contains(packageName))
            throw new IOException("所选游戏与系统 UID 归属不一致，请重新选择。");
        // A shared UID grants no sibling paths: every path below comes from this package's PM record.
        // Runtime quiescence is UID-wide, so an active/unknown shared peer still blocks file operations.
        List<File> apks=PackageTrust.installedApks(info);
        if(apks.size()>64)throw new IOException("所选游戏 APK 数量超出 Root 安全限制。");
        Map<String,String> hashes=new TreeMap<>();
        long total=0;
        for(File apk:apks) {
            total+=apk.length();
            if(total>8589934592L || !apk.isFile() || !apk.getAbsoluteFile().equals(apk.getCanonicalFile())
                    || hashes.put(apk.getPath(),PackageTrust.sha256(apk))!=null)
                throw new IOException("完整 APK 路径或大小不能确认。");
        }
        List<String> current=new ArrayList<>();
        for(Signature signature:PackageTrust.currentSigners(info))
            current.add(PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        Collections.sort(current);
        List<String> source=current;
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
                source=retained.sourceSignerCertificateSha256;sourceSet=retained.sourceApkSetSha256;
                retainedProvenance=true;
            }
        } catch(Exception unavailableProvenance) {
            // Keep existing receipts untouched. The actual signer gets its own source namespace;
            // unverified manifest metadata must never impersonate the original publisher.
        }
        String version=info.versionName==null || info.versionName.trim().isEmpty()?Long.toString(PackageTrust.versionCode(info)):info.versionName;
        return new SelectedRootGame(packageName,pm.getApplicationLabel(info.applicationInfo).toString(),version,
                PackageTrust.versionCode(info),info.firstInstallTime,info.lastUpdateTime,user,uid,info.applicationInfo.dataDir,
                current,source,sourceSet,hashes,retainedProvenance);
    }
    private static String sourceSet(PackageInfo info,Map<String,String> hashes)throws Exception {
        TreeMap<String,String> bySplit=new TreeMap<>();bySplit.put("",hashes.get(info.applicationInfo.sourceDir));
        String[] paths=info.applicationInfo.splitSourceDirs;
        if(paths!=null) {
            if(info.splitNames==null || info.splitNames.length!=paths.length)throw new IOException("分包名称或路径不完整。");
            for(int i=0;i<paths.length;i++)if(info.splitNames[i]==null || info.splitNames[i].isEmpty()
                    || bySplit.put(info.splitNames[i],hashes.get(paths[i]))!=null)throw new IOException("分包身份重复。");
        }
        StringBuilder bytes=new StringBuilder("pdsync-apk-set-v1\n");
        for(Map.Entry<String,String> entry:bySplit.entrySet())bytes.append(entry.getKey().length()).append(':')
                .append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        return PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(bytes.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
