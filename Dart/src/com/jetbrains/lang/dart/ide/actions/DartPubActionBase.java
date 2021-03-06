package com.jetbrains.lang.dart.ide.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.actions.ShowSettingsUtilImpl;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.DartProjectComponent;
import com.jetbrains.lang.dart.sdk.DartConfigurable;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import icons.DartIcons;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jetbrains.lang.dart.util.PubspecYamlUtil.PUBSPEC_YAML;

abstract public class DartPubActionBase extends AnAction implements DumbAware {
  private static final Logger LOG = Logger.getInstance("#com.jetbrains.lang.dart.ide.actions.DartPubActionBase");
  private static final String GROUP_DISPLAY_ID = "Dart Pub Tool";

  private static final AtomicBoolean ourInProgress = new AtomicBoolean(false);

  public DartPubActionBase() {
    super(DartIcons.Dart_16);
  }

  @Override
  public void update(AnActionEvent e) {
    //e.getPresentation().setText(getTitle());  "Pub: Build..." action name set in plugin.xml is different from its "Pub: Build" title
    final boolean visible = getModuleAndPubspecYamlFile(e) != null;
    e.getPresentation().setVisible(visible);
    e.getPresentation().setEnabled(visible && !ourInProgress.get());
  }

  @Nullable
  private static Pair<Module, VirtualFile> getModuleAndPubspecYamlFile(final AnActionEvent e) {
    final Module module = LangDataKeys.MODULE.getData(e.getDataContext());
    final PsiFile psiFile = CommonDataKeys.PSI_FILE.getData(e.getDataContext());

    if (module != null && psiFile != null && psiFile.getName().equalsIgnoreCase(PUBSPEC_YAML)) {
      final VirtualFile file = psiFile.getOriginalFile().getVirtualFile();
      return file != null ? Pair.create(module, file) : null;
    }
    return null;
  }

  @Nls
  protected abstract String getTitle();

  @Nullable
  protected abstract String[] calculatePubParameters(final Project project);

  protected abstract String getSuccessMessage();

  @Override
  public void actionPerformed(final AnActionEvent e) {
    final Pair<Module, VirtualFile> moduleAndPubspecYamlFile = getModuleAndPubspecYamlFile(e);
    if (moduleAndPubspecYamlFile == null) return;

    final Module module = moduleAndPubspecYamlFile.first;
    final VirtualFile pubspecYamlFile = moduleAndPubspecYamlFile.second;

    performPubAction(module, pubspecYamlFile, true);
  }

  public void performPubAction(final @NotNull Module module, final @NotNull VirtualFile pubspecYamlFile, final boolean allowModalDialogs) {
    DartSdk sdk = DartSdk.getGlobalDartSdk();

    if (sdk == null && allowModalDialogs) {
      final int answer = Messages.showDialog(module.getProject(), "Dart SDK is not configured",
                                             getTitle(), new String[]{"Configure SDK", "Cancel"}, 0, Messages.getErrorIcon());
      if (answer != 0) return;

      ShowSettingsUtilImpl.showSettingsDialog(module.getProject(), DartConfigurable.DART_SETTINGS_PAGE_ID, "");
      sdk = DartSdk.getGlobalDartSdk();
    }

    if (sdk == null) return;

    File pubFile = new File(DartSdkUtil.getPubPath(sdk));
    if (!pubFile.isFile() && allowModalDialogs) {
      final int answer =
        Messages.showDialog(module.getProject(), DartBundle.message("dart.sdk.bad.dartpub.path", pubFile.getPath()),
                            getTitle(), new String[]{"Configure SDK", "Cancel"}, 0, Messages.getErrorIcon());
      if (answer != 0) return;

      ShowSettingsUtilImpl.showSettingsDialog(module.getProject(), DartConfigurable.DART_SETTINGS_PAGE_ID, "");

      sdk = DartSdk.getGlobalDartSdk();
      if (sdk == null) return;

      pubFile = new File(sdk.getHomePath() + (SystemInfo.isWindows ? "/bin/pub.bat" : "/bin/pub"));
    }

    if (!pubFile.isFile()) return;

    final String[] pubParameters = calculatePubParameters(module.getProject());
    if (pubParameters != null) {
      queuePubTask(module, pubspecYamlFile, pubFile.getPath(), pubParameters, getTitle(), getSuccessMessage());
    }
  }

  private static void queuePubTask(@NotNull final Module module,
                                   @NotNull final VirtualFile pubspecYamlFile,
                                   @NotNull final String pubPath,
                                   @NotNull final String[] pubParameters,
                                   @NotNull final String actionTitle,
                                   @NotNull final String successMessage) {
    final Task.Backgroundable task = new Task.Backgroundable(module.getProject(), actionTitle, true) {
      public void run(@NotNull final ProgressIndicator indicator) {
        if (ourInProgress.compareAndSet(false, true)) {
          try {
            runPubProcessAndHandleItsResult(module, pubspecYamlFile, pubPath, pubParameters, actionTitle, successMessage, indicator);
          }
          finally {
            ourInProgress.set(false);
          }
        }
      }
    };

    task.queue();
  }

  private static void runPubProcessAndHandleItsResult(@NotNull final Module module,
                                                      @NotNull final VirtualFile pubspecYamlFile,
                                                      @NotNull final String pubPath,
                                                      @NotNull final String[] pubParameters,
                                                      @NotNull final String actionTitle,
                                                      @NotNull final String successMessage,
                                                      @NotNull final ProgressIndicator indicator) {
    final String presentableCommandLine = "pub " + StringUtil.join(pubParameters, " ");

    indicator.setText(DartBundle.message("dart.0.in.progress", presentableCommandLine));
    indicator.setIndeterminate(true);
    final GeneralCommandLine command = new GeneralCommandLine();
    command.setExePath(pubPath);
    command.setWorkDirectory(pubspecYamlFile.getParent().getPath());
    command.addParameters(pubParameters);

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        FileDocumentManager.getInstance().saveAllDocuments();
      }
    }, ModalityState.defaultModalityState());


    try {
      final ProcessOutput processOutput = new CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator);
      final String err = processOutput.getStderr().trim();

      LOG.debug(presentableCommandLine + ", exit code: " + processOutput.getExitCode() + ", err:\n" +
                err + "\nout:\n" + processOutput.getStdout());

      if (!indicator.isCanceled()) {
        if (err.isEmpty()) {
          Notifications.Bus.notify(new Notification(GROUP_DISPLAY_ID, actionTitle, successMessage, NotificationType.INFORMATION));
        }
        else {
          Notifications.Bus.notify(new Notification(GROUP_DISPLAY_ID, actionTitle, err, NotificationType.ERROR));
        }
      }

      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          DartProjectComponent.excludePackagesFolders(module, pubspecYamlFile);
          // refresh later than exclude, otherwise IDE may start indexing excluded folders
          pubspecYamlFile.getParent().refresh(true, true);
        }
      });
    }
    catch (ExecutionException ex) {
      LOG.error(ex);
      Notifications.Bus.notify(
        new Notification(GROUP_DISPLAY_ID, actionTitle, DartBundle.message("dart.pub.exception", ex.getMessage()), NotificationType.ERROR));
    }
  }
}
