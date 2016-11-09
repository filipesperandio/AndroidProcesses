/*
 * Copyright (C) 2016 Jared Rummler <jared.rummler@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jaredrummler.android.processes;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Class providing functionality to execute commands in a (root) shell
 */
public class Shell implements Closeable {

  private static final String END_OF_COMMAND = "--END-OF-COMMAND--";

  private static final String TAG = "Shell";

  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * <p>This code is adapted from java.lang.ProcessBuilder.start().</p>
   *
   * <p>The problem is that Android doesn't allow us to modify the map returned by ProcessBuilder.environment(), even
   * though the JavaDoc indicates that it should. This is because it simply returns the SystemEnvironment object that
   * System.getenv() gives us. The relevant portion in the source code is marked as "// android changed", so
   * presumably it's not the case in the original version of the Apache Harmony project.</p>
   *
   * <p>Note that simply passing the environment variables we want to Process.exec won't be good enough, since that
   * would override the environment we inherited completely.</p>
   *
   * <p>We needed to be able to set a CLASSPATH environment variable for our new process in order to use the
   * "app_process" command directly. Note: "app_process" takes arguments passed on to the Dalvik VM as well; this
   * might be an alternative way to set the class path.</p>
   *
   * @param command
   *     The name of the program to execute. E.g. "su" or "sh".
   * @param environment
   *     List of all environment variables (in 'key=value' format) or {@code null} for defaults.
   * @return new {@link Process} instance.
   * @throws IOException
   *     if the requested program could not be executed.
   */
  protected static Process runWithEnv(String command, String[] environment) throws IOException {
    String shell = command.substring(command.lastIndexOf("/") + 1);
    if (shell.equals("su")) {
      if (environment == null) {
        // On some versions of Android (ICS) LD_LIBRARY_PATH is unset when using su
        // We need to pass LD_LIBRARY_PATH over su for some commands to work correctly.
        // Is this still necessary???
        environment = new String[]{"LD_LIBRARY_PATH" + "=" + System.getenv("LD_LIBRARY_PATH")};
      }
    }
    if (environment != null) {
      Map<String, String> newEnvironment = new HashMap<>();
      newEnvironment.putAll(System.getenv());
      int split;
      for (String entry : environment) {
        if ((split = entry.indexOf("=")) >= 0) {
          newEnvironment.put(entry.substring(0, split), entry.substring(split + 1));
        }
      }
      int i = 0;
      environment = new String[newEnvironment.size()];
      for (Map.Entry<String, String> entry : newEnvironment.entrySet()) {
        environment[i] = entry.getKey() + "=" + entry.getValue();
        i++;
      }
    }
    return Runtime.getRuntime().exec(command, environment);
  }

  private final List<Command> commands = new ArrayList<>();
  private final OutputStreamWriter outputStream;
  private final BufferedReader stdoutReader;
  private final BufferedReader stderrReader;
  private final Process process;
  private Command command;
  private boolean close;

  private final Runnable shellInput = new Runnable() {

    @Override public void run() {
      try {
        int index = 0;
        while (true) {
          synchronized (commands) {
            while (!close && index >= commands.size()) {
              commands.wait();
            }
          }
          if (index < commands.size()) {
            Command next = commands.get(index);
            outputStream.write(next.getCommand());
            String line = "\necho " + END_OF_COMMAND + " " + index + " $?\n";
            outputStream.write(line);
            outputStream.flush();
            index++;
          } else if (close) {
            outputStream.write("\nexit 0\n");
            outputStream.flush();
            outputStream.close();
            return;
          }
        }
      } catch (IOException e) {
        Log.e(TAG, "Error while running a command", e);
      } catch (InterruptedException e) {
        Log.e(TAG, "Interrupted while writing command.", e);
      } finally {
        closeQuietly(outputStream);
      }
    }
  };

  private final Runnable shellOutput = new Runnable() {

    @Override public void run() {
      try {
        command = null;
        int index = 0;

        while (true) {
          String stdoutLine = stdoutReader.readLine();

          if (stdoutLine == null) {
            break; // terminate on EOF
          }

          if (command == null) {
            if (index >= commands.size()) {
              if (close) {
                break; // break on close after last command
              }
              continue;
            }
            // get current command
            command = commands.get(index);
          }

          int pos = stdoutLine.indexOf(END_OF_COMMAND);

          if (pos == -1) {
            command.onReadStdout(command.id, stdoutLine);
          } else if (pos > 0) {
            command.onReadStdout(command.id, stdoutLine.substring(0, pos));
          }

          if (pos >= 0) {
            stdoutLine = stdoutLine.substring(pos);
            String fields[] = stdoutLine.split(" ");

            if (fields.length >= 2) {
              int id = tryParseInt(fields[1]);

              if (id == index) {
                try {
                  while (stderrReader.ready() && command != null) {
                    String line = stderrReader.readLine();
                    if (line == null) break;
                    command.onReadStderr(command.id, line);
                  }
                } catch (Exception e) {
                  Log.e(TAG, "Error reading stderr", e);
                }

                command.setExitCode(tryParseInt(fields[2]));

                // go to next command
                index++;

                command = null;
              }
            }
          }
        }

        // Read all output
        process.waitFor();
        destroyShellProcess();

        while (index < commands.size()) {
          if (command == null) {
            command = commands.get(index);
          }
          command.setExitCode(-1);
          index++;
          command = null;
        }
      } catch (Exception e) {
        Log.e(TAG, "Error while reading output", e);
      } finally {
        closeQuietly(stdoutReader);
        closeQuietly(stderrReader);
      }
    }
  };

  public Shell(String shell) throws ShellNotFoundException {
    this(shell, null);
  }

  public Shell(String shell, String[] environment) throws ShellNotFoundException {
    try {
      process = runWithEnv(shell, environment);
      // stderr is redirected to stdout, defined in Command.getCommand()
      stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
      stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));
      outputStream = new OutputStreamWriter(process.getOutputStream(), "UTF-8");
      // Write a test command
      outputStream.write("echo START\n");
      outputStream.flush();
      // Process the output
      String line = stdoutReader.readLine();
      if (line == null) {
        closeQuietly(stdoutReader);
        closeQuietly(stderrReader);
        closeQuietly(outputStream);
        throw new ShellNotFoundException("Access was denied or this executeable is not a shell!");
      }
      if (!line.equals("START")) {
        destroyShellProcess();
        closeQuietly(stdoutReader);
        closeQuietly(stderrReader);
        closeQuietly(outputStream);
        throw new ShellNotFoundException("Unable to start shell, unexpected output \"" + line + "\"");
      }
      // Start two threads, one to handle the input and one to handle the output.
      new Thread(shellInput, "Shell Input").start();
      new Thread(shellOutput, "Shell Output").start();
    } catch (IOException e) {
      throw new ShellNotFoundException(e);
    }
  }

  /**
   * Execute commands in the shell. Default timeout is 20 seconds.
   *
   * @param commands
   *     The command(s) to run in the shell.
   * @return a {@link CommandResult} containing information about the last run command.
   */
  public CommandResult execute(String... commands) {
    return execute(20_000, commands);
  }

  /**
   * Execute commands in the shell.
   *
   * @param timeout
   *     Time in milliseconds before a {@link TimeoutException} is thrown.
   * @param commands
   *     The command(s) to run in the shell.
   * @return a {@link CommandResult} containing information about the last run command.
   */
  public CommandResult execute(int timeout, String... commands) {
    SimpleCommand command = new SimpleCommand(timeout, commands);
    CommandResult result = new CommandResult();
    try {
      add(command).waitForFinish();
      return command.getCommandResult();
    } catch (Exception e) {
      Log.e("Shell", "Error running commands", e);
    }
    return result;
  }

  /**
   * Add command to shell queue
   *
   * @param command
   *     the command to add to the queue.
   * @return the command.
   * @throws IOException
   *     if the shell is closed.
   */
  public Command add(Command command) throws IOException {
    if (close) {
      throw new IOException("Unable to add commands to a closed shell");
    }
    synchronized (commands) {
      commands.add(command);
      // set shell on the command object, to know where the command is running on
      command.addedToShell(this, (commands.size() - 1));
      commands.notifyAll();
    }
    return command;
  }

  @Override public void close() throws IOException {
    synchronized (commands) {
      close = true;
      commands.notifyAll();
    }
  }

  /* Destroy shell process considering that the process could already be terminated */
  private void destroyShellProcess() {
    try {
      // Throws IllegalThreadStateException if the process is running.
      process.exitValue();
    } catch (IllegalThreadStateException e) {
      // Only call destroy() if the process is still running.
      // Calling it for a terminated process will not crash,
      // However, it will spam the log with INFO messages.
      // "Failed to destroy process" and "kill failed: ESRCH (No such process)".
      process.destroy();
    }
  }

  private int tryParseInt(String string) {
    try {
      return Integer.parseInt(string);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  public Command getCommand() {
    return command;
  }

  public boolean isClosed() {
    return close;
  }

  public static class SimpleCommand extends Command {

    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    public SimpleCommand(int timeout, String... command) {
      super(timeout, command);
    }

    public SimpleCommand(String... command) {
      super(command);
    }

    @Override public void afterExecution(int id, int exitCode) {
    }

    @Override public void commandFinished(int id) {
    }

    @Override public void onReadStdout(int id, String line) {
      stdout.append(line).append('\n');
    }

    @Override public void onReadStderr(int id, String line) {
      stderr.append(line).append('\n');
    }

    public CommandResult getCommandResult() {
      return new CommandResult(getStdout(), getStderr(), exitCode);
    }

    public String getStdout() {
      final String output = stdout.toString();
      if (TextUtils.isEmpty(output)) return "";
      return output.substring(0, output.length() - 1/* remove trailing newline */);
    }

    public String getStderr() {
      final String output = stderr.toString();
      if (TextUtils.isEmpty(output)) return "";
      return output.substring(0, output.length() - 1 /* remove trailing newline */);
    }

  }

  public static class CommandResult {

    /** The exit value returned by the last run command. */
    public final int exitCode;

    /** Standard error */
    public final String stderr;

    /** Standard output */
    public final String stdout;

    public CommandResult() {
      this("", "", -1);
    }

    public CommandResult(String stdout, String stderr, int exitCode) {
      this.stdout = stdout;
      this.stderr = stderr;
      this.exitCode = exitCode;
    }

    /**
     * @return {@code true} if the {@link #exitCode} is equal to 0.
     */
    public boolean success() {
      return exitCode == 0;
    }

    /**
     * @return {@code true} if stdout is not empty or {@code null}.
     */
    public boolean hasOutput() {
      return !TextUtils.isEmpty(stdout);
    }

  }

  public abstract static class Command {

    public final String[] commands;
    public final int timeout;
    public int exitCode = -1;
    public int id;
    public boolean isFinished;
    public Shell shell;

    public abstract void afterExecution(int id, int exitCode);

    public abstract void commandFinished(int id);

    public abstract void onReadStdout(int id, String line);

    public abstract void onReadStderr(int id, String line);

    public Command(String... commands) {
      this(20000, commands);
    }

    public Command(int timeout, String... commands) {
      this.commands = commands;
      this.timeout = timeout;
    }

    /**
     * This is called from Shell after adding it
     *
     * @param shell
     *     The shell to run the commands in
     * @param id
     *     The command ID
     */
    protected void addedToShell(Shell shell, int id) {
      this.shell = shell;
      this.id = id;
    }

    /**
     * @return the command string to be executed on the shell.
     */
    protected String getCommand() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0, len = commands.length; i < len; i++) {
        if (i > 0) sb.append('\n');
        sb.append(commands[i]);
      }
      return sb.toString();
    }

    public void processAfterExecution(int exitCode) {
      afterExecution(id, exitCode);
    }

    public void setExitCode(int code) {
      synchronized (this) {
        exitCode = code;
        isFinished = true;
        commandFinished(id);
        notifyAll();
      }
    }

    public void terminate() {
      try {
        shell.close();
        setExitCode(-1);
      } catch (IOException ignored) {
      }
    }

    /**
     * Waits for this command to finish and forwards exitCode into {@link #afterExecution(int,
     * int)}
     *
     * @throws TimeoutException
     *     if the command timed out.
     */
    public void waitForFinish() throws TimeoutException {
      synchronized (this) {
        while (!isFinished) {
          try {
            this.wait(timeout);
          } catch (InterruptedException ignored) {
          }
          if (!isFinished) {
            terminate();
            throw new TimeoutException("Timeout has occurred.");
          }
        }

        processAfterExecution(exitCode);
      }
    }

  }

  public static class SU {

    private static volatile Shell shell;
    private static int defaultTimeout = 20_000;

    /**
     * @return the shell instance. A new one is created if the shell has been closed or is null.
     */
    public static Shell getShell() {
      if (shell == null || shell.isClosed()) {
        try {
          shell = new Shell("su");
        } catch (ShellNotFoundException e) {
          Log.d(TAG, "'su' not found");
        }
      }
      return shell;
    }

    /**
     * Detects whether or not superuser access is available, by checking the output of the "id" command if available,
     * checking if a shell runs at all otherwise
     *
     * @return True if superuser access available
     */
    public static boolean available() {
      CommandResult result = run("id");
      if (result.stdout != null && result.stdout.contains("uid=0")) {
        return true;
      }
      result = run("echo -BOC-");
      return result.stdout != null && result.stdout.contains("-BOC-");
    }

    /**
     * Execute commands in the shell.
     *
     * @param timeout
     *     Time in milliseconds before a {@link TimeoutException} is thrown.
     * @param commands
     *     The command(s) to run in the shell.
     * @return a {@link CommandResult} containing information about the last run command.
     */
    public static CommandResult run(int timeout, String... commands) {
      Shell shell = getShell();
      CommandResult result;
      if (shell != null) {
        result = shell.execute(timeout, commands);
      } else {
        result = new CommandResult();
      }
      return result;
    }

    /**
     * Execute commands in the shell.
     *
     * @param commands
     *     The command(s) to run in the shell.
     * @return a {@link CommandResult} containing information about the last run command.
     */
    public static CommandResult run(String... commands) {
      return run(defaultTimeout, commands);
    }

    /**
     * Close the shell instance.
     */
    public static void close() {
      if (shell != null) {
        closeQuietly(shell);
        shell = null;
      }
    }

    /**
     * Set the timeout before a command quits. Original value is 20 seconds.
     *
     * @param timeout
     *     Time in milliseconds before a {@link TimeoutException} is thrown.
     */
    public static void setDefaultTimeout(int timeout) {
      defaultTimeout = timeout;
    }

  }

  /**
   * Exception thrown when a shell could not be opened.
   */
  public static class ShellNotFoundException extends RuntimeException {

    /**
     * Constructs a new {@code Exception} with the current stack trace and the specified detail message.
     *
     * @param detailMessage
     *     the detail message for this exception.
     */
    public ShellNotFoundException(String detailMessage) {
      super(detailMessage);
    }

    /**
     * Constructs a new {@code Exception} with the current stack trace and the specified cause.
     *
     * @param throwable
     *     the cause of this exception.
     */
    public ShellNotFoundException(Throwable throwable) {
      super(throwable);
    }

  }

}
