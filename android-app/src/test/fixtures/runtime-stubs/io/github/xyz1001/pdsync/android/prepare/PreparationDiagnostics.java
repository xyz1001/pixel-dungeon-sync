// Host-only adapter double. Real preparation failure/export is covered by preparation tests.
package io.github.xyz1001.pdsync.android.prepare;
import java.util.Map;
import java.util.Collections;
public final class PreparationDiagnostics {
 public static final class Failure extends java.io.IOException { public final Map<String,String> evidence; public Failure(Map<String,String> evidence) { this.evidence=evidence; } }
 public static Failure find(Throwable error) { for(int i=0;error!=null&&i<8;i++,error=error.getCause()) if(error instanceof Failure)return (Failure)error;return null; }
 public static Map<String,String> details(Throwable error) { Failure f=find(error);return f==null?Collections.emptyMap():f.evidence; }
}
