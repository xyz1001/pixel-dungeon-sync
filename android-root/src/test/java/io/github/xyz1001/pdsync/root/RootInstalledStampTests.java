package io.github.xyz1001.pdsync.root;

import java.io.IOException;
import java.util.*;

/** Pure metadata-fence tests, no Android/Root/native/filesystem operation. */
public final class RootInstalledStampTests {
    private static int checks;
    private static final String HASH=String.join("",Collections.nCopies(64,"a"));
    private static final String PATH="/data/app/com.example.game-a/base.apk";
    private static RootBinding binding(String hash)throws IOException {
        return new RootBinding("com.example.game",0,10123,"/data/user/0/com.example.game",HASH,Collections.singletonMap(PATH,hash));
    }
    private static void ok(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        RootBinding binding=binding(HASH);
        Map<String,String> values=new TreeMap<>();values.put(PATH,"dev:inode:owner:mode:size:mtime:ctime");
        RootInstalledStamp original=new RootInstalledStamp(binding,896,100,200,values);
        ok(original.same(new RootInstalledStamp(binding,896,100,200,values)));
        ok(original.binds(binding));ok(!original.same(null));
        for(int field=0;field<3;field++) {
            long[] times={896,100,200};times[field]++;
            ok(!original.same(new RootInstalledStamp(binding,times[0],times[1],times[2],values)));
        }
        String other=String.join("",Collections.nCopies(64,"b"));
        ok(!original.binds(binding(other)));
        ok(!original.same(new RootInstalledStamp(binding(other),896,100,200,values)));
        for(String metadata:Arrays.asList("new inode","new size","new mtime","new owner","new mode")) {
            Map<String,String> changed=new TreeMap<>(values);changed.put(PATH,metadata);
            ok(!original.same(new RootInstalledStamp(binding,896,100,200,changed)));
        }
        values.put(PATH,"modified after construction");
        ok(original.same(new RootInstalledStamp(binding,896,100,200,Collections.singletonMap(PATH,"dev:inode:owner:mode:size:mtime:ctime"))));
        for(Map<String,String> invalid:Arrays.asList(Collections.<String,String>emptyMap(),Collections.singletonMap(PATH,""),Collections.singletonMap(PATH,(String)null),Collections.singletonMap("/data/app/other/base.apk","x"))) {
            try {new RootInstalledStamp(binding,896,100,200,invalid);throw new AssertionError("invalid accepted");}
            catch(IOException expected){checks++;}
        }
        for(int field=0;field<3;field++) {
            long[] times={896,100,200};times[field]=-1;
            try{new RootInstalledStamp(binding,times[0],times[1],times[2],values);throw new AssertionError("negative accepted");}
            catch(IOException expected){checks++;}
        }
        System.out.println("RootInstalledStampTests: "+checks+" assertions (metadata only; not Android/root validation)");
    }
}
