/*
 * This file is part of RskJ
 * Copyright (C) 2023 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.util.cli;

import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CommandLineFixture {

    public static class CustomProcess {
        private final String output;

        public CustomProcess(String output) {
            this.output = output;
        }

        public String getOutput() {
            return output;
        }

    }

    private CommandLineFixture() {
    }


    public static String readProcStream(InputStream in) throws IOException {
        byte[] bytesAvailable = new byte[in.available()];
        in.read(bytesAvailable, 0, bytesAvailable.length);
        return new String(bytesAvailable);
    }

    public static CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit, Consumer<Process> beforeDestroyFn) throws InterruptedException, IOException {
        return runCommand(cmd, timeout, timeUnit, beforeDestroyFn, true);
    }

    public static CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return runCommand(cmd, timeout, timeUnit, null, true);
    }

    public static CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit, boolean assertProcExitCode) throws InterruptedException, IOException {
        return runCommand(cmd, timeout, timeUnit, null, assertProcExitCode);
    }

    public static CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit, Consumer<Process> beforeDestroyFn, boolean assertProcExitCode) throws InterruptedException, IOException {
        Process proc = Runtime.getRuntime().exec(cmd);
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        Thread outThread = new Thread(new StreamGobbler(proc.getInputStream(), line -> {
            synchronized (stdoutBuilder) {
                stdoutBuilder.append(line).append(System.lineSeparator());
            }
        }), "cmdline-stdout-gobbler");
        outThread.setDaemon(true);
        outThread.start();

        Thread errThread = new Thread(new StreamGobbler(proc.getErrorStream(), line -> {
            synchronized (stderrBuilder) {
                stderrBuilder.append(line).append(System.lineSeparator());
            }
        }), "cmdline-stderr-gobbler");
        errThread.setDaemon(true);
        errThread.start();

        boolean finished = false;
        try {
            finished = proc.waitFor(timeout, timeUnit);

            if (beforeDestroyFn != null) {
                beforeDestroyFn.accept(proc);
            }
        } finally {
            destroyProcess(proc);
            try {
                outThread.join(2000);
                errThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String procOutput;
        String procError;
        synchronized (stdoutBuilder) {
            procOutput = stdoutBuilder.toString();
        }
        synchronized (stderrBuilder) {
            procError = stderrBuilder.toString();
        }

        if (assertProcExitCode && finished) {
            System.out.println("procOutput: " + procOutput);
            System.out.println("procError:" + procError);
            Assertions.assertEquals(0, proc.exitValue(), "Proc exited with value: " + proc.exitValue());
        }

        return new CustomProcess(procOutput);
    }

    public static void destroyProcess(Process proc) {
        if (proc == null || !proc.isAlive()) {
            return;
        }

        try {
            proc.descendants().forEach(ph -> {
                try {
                    ph.destroy();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }

        proc.destroy();

        try {
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                try {
                    proc.descendants().forEach(ph -> {
                        try {
                            ph.destroyForcibly();
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
                proc.destroyForcibly();
                proc.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                proc.descendants().forEach(ph -> {
                    try {
                        ph.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            proc.destroyForcibly();
        }
    }
}
