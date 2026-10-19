package dev.shellmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 工具定义与调用。 */
public final class Tools {

    public static final class Result {
        public final String text;
        public final boolean isError;
        Result(String text, boolean isError) { this.text = text; this.isError = isError; }
    }

    private Tools() {}

    public static JSONArray list() throws JSONException {
        JSONArray tools = new JSONArray();

        // shell
        JSONObject props = new JSONObject();
        props.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "要执行的命令，例如 getprop ro.product.model 或 pm list packages"));
        props.put("root", new JSONObject()
                .put("type", "boolean")
                .put("description", "是否以 root(su) 执行，默认 false")
                .put("default", false));
        props.put("timeout", new JSONObject()
                .put("type", "integer")
                .put("description", "超时秒数，默认 30")
                .put("default", 30));
        JSONObject shellSchema = new JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", new JSONArray().put("command"));
        tools.put(new JSONObject()
                .put("name", "shell")
                .put("description", "在本机安卓系统执行 shell 命令并返回输出。root=true 时用 su 以 root 身份执行（设备需已 root）。")
                .put("inputSchema", shellSchema));

        // device_info
        tools.put(new JSONObject()
                .put("name", "device_info")
                .put("description", "返回设备品牌/型号、安卓版本及 root(su) 可用状态。")
                .put("inputSchema", new JSONObject()
                        .put("type", "object").put("properties", new JSONObject())));

        return tools;
    }

    public static Result call(String name, JSONObject args) {
        if ("shell".equals(name)) {
            String cmd = args.optString("command", "").trim();
            if (cmd.isEmpty()) return new Result("ERROR: command 不能为空", true);
            boolean root = args.optBoolean("root", false);
            int timeout = args.optInt("timeout", 30);
            return new Result(Shell.run(cmd, root, timeout), false);
        }
        if ("device_info".equals(name)) {
            String brand = Shell.run("getprop ro.product.brand", false, 10);
            String model = Shell.run("getprop ro.product.model", false, 10);
            String rel = Shell.run("getprop ro.build.version.release", false, 10);
            String sdk = Shell.run("getprop ro.build.version.sdk", false, 10);
            boolean hasSu = Shell.hasSu();
            String rootId = hasSu ? Shell.run("id -u", true, 10) : "(未检测到 su)";
            String text = "品牌/型号 : " + brand + " " + model + "\n"
                    + "Android   : " + rel + " (SDK " + sdk + ")\n"
                    + "su 路径   : " + Shell.suPath() + "  存在=" + hasSu + "\n"
                    + "root 测试 : id -u => " + rootId;
            return new Result(text, false);
        }
        return new Result("ERROR: 未知工具 " + name, true);
    }
}
