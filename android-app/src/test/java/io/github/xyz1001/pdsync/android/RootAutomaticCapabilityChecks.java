package io.github.xyz1001.pdsync.android;

/** Pure static capability matrix; no Context, Service, root process or native call is instantiated. */
public final class RootAutomaticCapabilityChecks {
    public static void main(String[] args) {
        int checks=0;
        for(GameAccessBackend.Mode mode:GameAccessBackend.Mode.values())for(int mask=0;mask<16;mask++) {
            GameAccessBackend.Capabilities caps=new GameAccessBackend.Capabilities((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0);
            boolean expected=mode!=GameAccessBackend.Mode.LEGACY_HELPER_BRIDGE&&(mask&1)!=0&&(mask&4)!=0;
            if(SyncRuntime.backendSupportsAutomatic(mode,caps)!=expected)throw new AssertionError("Wrong automatic capability gate "+mode+":"+mask);
            checks++;
        }
        if(SyncRuntime.backendSupportsAutomatic(null,new GameAccessBackend.Capabilities(true,true,true,true))
                ||SyncRuntime.backendSupportsAutomatic(GameAccessBackend.Mode.ROOT_DIRECT,null))throw new AssertionError("Missing state allowed");
        System.out.println("Automatic backend capability checks passed: "+(checks+2)+"; no Android/root execution");
    }
}
