package com.sevtinge.hyperceiler.utils.shell;

import com.sevtinge.hyperceiler.callback.ITAG;
import com.sevtinge.hyperceiler.utils.log.AndroidLogUtils;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可以执行多条 Shell 命令并实时获取结果的 Shell 工具。
 * 本工具使用简单的方法延续 Su/Sh 命令执行窗口，使得调用者无须频繁的执行 Su。
 * 调用示例:
 * <pre> {@code
 * new ShellUtils.OpenShellExecWindow("ls", true, true, new ShellUtils.ICommandOutPut() {
 *             @Override
 *             public void readOutput(String out, boolean finish) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "out: " + out + " finish: " + finish);
 *             }
 *
 *             @Override
 *             public void readError(String out) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "error: " + out);
 *             }
 *
 *             @Override
 *             public void result(String command, int result) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "command: " + command + " result: " + result);
 *
 *             }
 *         })
 *             .sync()
 *             .append("echo done")
 *             .sync()
 *             .append("touch /data/adb/2")
 *             .sync()
 *             .close();
 * }
 * 请在适当的时机调用 {@link ShellExec#close} 用来释放资源。
 * int result 值为执行全部命令后 exit 的返回值，可以用来判断命令是否执行成功。
 * 重写 {@link ICommandOutPut#result(String, int)} 方法可以实时获取每条命令的执行结果。
 * @author 焕晨HChen
 * @noinspection UnusedReturnValue
 */
public class ShellExec {
    private Process process;
    private DataOutputStream os;
    private static IPassCommands passCommands0;
    private static IPassCommands passCommands1;

    private boolean result;
    private int count = 1;

    protected static void setICommand(IPassCommands pass, int mode) {
        switch (mode) {
            case 0 -> passCommands0 = pass;
            case 1 -> passCommands1 = pass;
        }
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root, ICommandOutPut iCommandOutPut) {
        this("", root, false, iCommandOutPut);
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root, boolean result, ICommandOutPut iCommandOutPut) {
        this("", root, result, iCommandOutPut);
    }

    /**
     * 构造函数，完成初始化等任务。
     *
     * @param command        需要执行的第一个命令，可以留空。
     * @param root           是否使用 Root 身份执行。
     * @param result         是否需要获取每条命令的返回值。
     * @param iCommandOutPut 回调方法。
     */
    public ShellExec(String command, boolean root, boolean result, ICommandOutPut iCommandOutPut) {
        try {
            OutPut.setOutputListen(iCommandOutPut);
            Error.setOutputListen(iCommandOutPut);
            this.result = result;
            boolean run = command != null && !("".equals(command));
            process = Runtime.getRuntime().exec(root ? "su" : "sh");
            os = new DataOutputStream(process.getOutputStream());
            if (run) {
                os.write(command.getBytes());
                os.writeBytes("\n");
                os.flush();
            }
            if (result) {
                Error error = new Error(process.getErrorStream(), this);
                OutPut output = new OutPut(process.getInputStream(), this);
                if (passCommands0 != null)
                    passCommands0.passCommands(command);
                if (passCommands1 != null)
                    passCommands1.passCommands(command);
                error.start();
                output.start();
                if (run) done(0);
            }
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow E", e);
        }
    }

    /**
     * 追加需要执行的命令。
     *
     * @param command 命令
     * @return this
     */
    public ShellExec append(String command) {
        try {
            if (result) {
                if (passCommands0 != null)
                    passCommands0.passCommands(command);
                if (passCommands1 != null)
                    passCommands1.passCommands(command);
            }
            os.write(command.getBytes());
            os.writeBytes("\n");
            os.flush();
            if (result) {
                done(count);
                count = count + 1;
            }
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow append E", e);
        }
        return this;
    }

    /**
     * 同步命令。
     * 进程将会在该条命令完全执行完毕并输出结束内容前等待。
     *
     * @return this
     */
    public ShellExec sync() {
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow sync E", e);
            }
        }
        return this;
    }

    private void done(int count) {
        try {
            os.writeBytes("result=$?; string=\"The execution of command <" + count + "> is complete. Return value: <$result>\"; " +
                "if [[ $result != 0 ]]; then echo $string 1>&2; else echo $string 2>/dev/null; fi");
            // os.writeBytes("echo \"The execution of command <" + count + "> is complete. Return value: <$?>\" 1>&2 2>&1");
            os.writeBytes("\n");
            os.flush();
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow done E", e);
        }
    }

    /**
     * 关闭 ShellWindow。
     *
     * @return 本 process 的最终执行结果
     */
    public int close() {
        int result = -1;
        try {
            os.writeBytes("exit\n");
            os.flush();
            result = process.waitFor();
            if (process != null) {
                process.destroy();
            }
            if (os != null) {
                os.close();
            }
            return result;
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow close E", e);
        } catch (InterruptedException f) {
            AndroidLogUtils.LogE(ITAG.TAG, "OpenShellExecWindow getResult E", f);
        }
        return result;
    }

    private void log(String log) {
        AndroidLogUtils.LogI(ITAG.TAG, log);
    }

    protected interface IPassCommands {
        void passCommands(String command);
    }

    private static class OutPut extends Thread {
        private final InputStream mInput;
        private final Pattern pattern;
        private final Command command;
        private final String contrast;
        private final Object object;

        private static ICommandOutPut mICommandOutPut;

        public OutPut(InputStream inputStream, Object o) {
            contrast = "The execution of command <";
            pattern = Pattern.compile(".*<(\\d+)>.*<(\\d+)>.*");
            command = new Command(0);
            object = o;
            mInput = inputStream;
        }

        public static void setOutputListen(ICommandOutPut iCommandOutPut) {
            mICommandOutPut = iCommandOutPut;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(mInput))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains(contrast)) {
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            String count = matcher.group(1);
                            String result = matcher.group(2);
                            if (result != null && count != null) {
                                mICommandOutPut.result(command.passCommands.get(Integer.parseInt(count)), Integer.parseInt(result));
                                mICommandOutPut.readOutput("Finish!!", true);
                                synchronized (object) {
                                    try {
                                        object.notify();
                                    } catch (IllegalMonitorStateException e) {
                                    }
                                }
                                continue;
                            }
                        }
                    }
                    mICommandOutPut.readOutput(line, false);
                }
            } catch (IOException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell OutPut run E", e);
            }
        }


        private void log(String log) {
            AndroidLogUtils.LogI(ITAG.TAG, log);
        }
    }

    private static class Error extends Thread {
        private final InputStream mInput;
        private final Object object;
        private final Pattern pattern;
        private final Command command;
        private static ICommandOutPut mICommandOutPut;

        public Error(InputStream inputStream, Object o) {
            pattern = Pattern.compile(".*<(\\d+)>.*<(\\d+)>.*");
            command = new Command(1);
            mInput = inputStream;
            object = o;
        }

        public static void setOutputListen(ICommandOutPut iCommandOutPut) {
            mICommandOutPut = iCommandOutPut;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(mInput))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        String count = matcher.group(1);
                        String result = matcher.group(2);
                        if (result != null && count != null) {
                            mICommandOutPut.result(command.passCommands.get(Integer.parseInt(count)), Integer.parseInt(result));
                            synchronized (object) {
                                try {
                                    object.notify();
                                } catch (IllegalMonitorStateException e) {
                                }
                            }
                            continue;
                        }
                    }
                    mICommandOutPut.readError(line);
                }
            } catch (IOException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell Error run E", e);
            } catch (NumberFormatException f) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell get result E", f);
            }
        }

        private void log(String log) {
            AndroidLogUtils.LogI(ITAG.TAG, log);
        }
    }

    protected static class Command implements IPassCommands {
        public ArrayList<String> passCommands = new ArrayList<>();

        public Command(int mode) {
            setICommand(this, mode);
        }

        @Override
        public void passCommands(String command) {
            passCommands.add(command);
        }
    }


    public interface ICommandOutPut {
        /**
         * 重写本方法可以实时获取常规流数据。
         *
         * @param out 常规流数据
         */
        default void readOutput(String out, boolean finish) {
        }

        /**
         * 重写本方法可以实时获取错误流数据。
         *
         * @param out 错误流数据
         */
        default void readError(String out) {
        }

        /**
         * 重写本方法可以实时获取每条命令的执行结果。
         *
         * @param command 命令
         * @param result  结果
         */
        default void result(String command, int result) {
        }
    }
}
