package dev.shellmcp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText portInput;
    private CheckBox lanCheck;
    private Button toggleBtn;
    private TextView statusView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        portInput = findViewById(R.id.portInput);
        lanCheck = findViewById(R.id.lanCheck);
        toggleBtn = findViewById(R.id.toggleBtn);
        statusView = findViewById(R.id.statusView);
        statusView.setMovementMethod(new ScrollingMovementMethod());

        portInput.setText(String.valueOf(McpService.PORT));

        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void onToggle() {
        if (McpService.RUNNING) {
            McpService.stop(this);
            Toast.makeText(this, "正在停止…", Toast.LENGTH_SHORT).show();
        } else {
            int port;
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "端口号无效", Toast.LENGTH_SHORT).show();
                return;
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "端口需在 1–65535", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean lan = lanCheck.isChecked();
            McpService.start(this, port, lan);
            Toast.makeText(this, "正在启动…", Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh() {
        boolean running = McpService.RUNNING;
        toggleBtn.setText(running ? "停止服务" : "启动服务");
        portInput.setEnabled(!running);
        lanCheck.setEnabled(!running);

        StringBuilder sb = new StringBuilder();
        if (running) {
            String host = McpService.LAN ? "0.0.0.0（局域网）" : "127.0.0.1（仅本机）";
            sb.append("● 运行中\n\n");
            sb.append("地址: http://").append(McpService.LAN ? "0.0.0.0" : "127.0.0.1")
              .append(':').append(McpService.PORT).append("/mcp\n");
            sb.append("绑定: ").append(host).append('\n');
            sb.append("Root(su): ").append(Shell.hasSu() ? "可用 " + Shell.suPath() : "未检测到").append('\n');
            sb.append("\n在 MCP 客户端里添加：\n");
            sb.append("· Transport: Streamable HTTP\n");
            sb.append("· URL: 上面的地址\n");
            sb.append("· 无需 Key/Header");
        } else {
            sb.append("○ 已停止\n\n");
            sb.append("Root(su): ").append(Shell.hasSu() ? "可用 " + Shell.suPath() : "未检测到（root 命令会失败）").append('\n');
            if (McpService.LAST_ERROR != null) {
                sb.append("\n上次错误: ").append(McpService.LAST_ERROR);
                if (McpService.LAST_ERROR.contains("EADDRINUSE")
                        || McpService.LAST_ERROR.toLowerCase().contains("address already in use")) {
                    sb.append("\n（端口被占用，换一个端口）");
                }
            }
        }
        statusView.setText(sb.toString());
    }

    @Override protected void onResume() { super.onResume(); handler.post(poller); }
    @Override protected void onPause()  { super.onPause();  handler.removeCallbacks(poller); }
}
