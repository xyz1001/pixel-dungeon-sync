package io.github.xyz1001.pdsync.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


public final class ActivationActivity extends Activity {
    @Override // android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setResult(0);
        try {
            MainCallerAuthenticator.authenticateActivity(this, getCallingPackage());
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            linearLayout.setGravity(16);
            int iRound = Math.round(getResources().getDisplayMetrics().density * 24.0f);
            linearLayout.setPadding(iRound, iRound, iRound, iRound);
            TextView textView = new TextView(this);
            textView.setText("存档桥接已打开");
            textView.setTextSize(24.0f);
            linearLayout.addView(textView);
            TextView textView2 = new TextView(this);
            textView2.setText("返回同步器后会重新检查连接。此步骤不发起存档同步，也不表示访问已验证。游戏的原 Application 仍会在桥接进程初始化，具体游戏须经过真机验证。");
            textView2.setTextSize(17.0f);
            textView2.setPadding(0, iRound, 0, iRound);
            linearLayout.addView(textView2);
            Button button = new Button(this);
            button.setText("返回同步器");
            button.setOnClickListener(new View.OnClickListener() { // from class: io.github.xyz1001.pdsync.bridge.ActivationActivity$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ActivationActivity.this.m0xdf076962(view);
                }
            });
            linearLayout.addView(button);
            setContentView(linearLayout);
        } catch (SecurityException unused) {
            finish();
        }
    }

    
     void m0xdf076962(View view) {
        setResult(-1);
        finish();
    }
}
