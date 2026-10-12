// Host-only test double. Never package this fixture into an APK.
package android.app;
public class Service {
 public int notificationId, foregroundType=-1, calls;
 public Notification notification;
 public void startForeground(int id,Notification value) { notificationId=id;notification=value;calls++; }
 public void startForeground(int id,Notification value,int type) {
  android.os.Build.requireApi(29);notificationId=id;notification=value;foregroundType=type;calls++;
 }
}
