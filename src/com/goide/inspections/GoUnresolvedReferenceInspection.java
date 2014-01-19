package com.goide.inspections;

import com.goide.jps.model.JpsGoSdkType;
import com.goide.psi.*;
import com.intellij.codeInspection.LocalQuickFixBase;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Platform;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
import static com.intellij.codeInspection.ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;

public class GoUnresolvedReferenceInspection extends GoInspectionBase {
  @Override
  protected void checkFile(PsiFile file, final ProblemsHolder problemsHolder) {
    if (!(file instanceof GoFile)) return;
    file.accept(new GoRecursiveVisitor() {
      @Override
      public void visitReferenceExpression(@NotNull GoReferenceExpression o) {
        super.visitReferenceExpression(o);
        PsiReference reference = o.getReference();
        GoReferenceExpression qualifier = o.getQualifier();
        PsiReference qualifierRef = qualifier != null ? qualifier.getReference() : null;
        PsiElement qualifierResolve = qualifierRef != null ? qualifierRef.resolve() : null;
        if (qualifier != null && qualifierResolve == null) return;
        if (reference == null || reference.resolve() == null) {
          PsiElement id = o.getIdentifier();
          String name = id.getText();
          problemsHolder.registerProblem(id, "Unresolved reference " + "'" + name + "'", LIKE_UNKNOWN_SYMBOL);
        }
      }

      @Override
      public void visitImportString(@NotNull GoImportString o) {
        if (o.getTextLength() < 2) return;
        PsiReference reference = o.getReference();
        PsiElement resolve = reference != null ? reference.resolve() : null;
        if (resolve == null) {
          String text = StringUtil.unquoteString(o.getText());
          boolean spaces = StringUtil.isEmptyOrSpaces(text);
          ProblemHighlightType type = spaces ? GENERIC_ERROR_OR_WARNING : LIKE_UNKNOWN_SYMBOL;
          TextRange range = spaces ? TextRange.create(0, o.getTextLength()) : TextRange.create(1, o.getTextLength() - 1);
          problemsHolder.registerProblem(o, "Unresolved import " + "'" + text + "'", type, range,
                                         getSdkPath(o) == null ? null : new GoGetPackageFix(text));
        }
      }

      @Override
      public void visitTypeReferenceExpression(@NotNull GoTypeReferenceExpression o) {
        super.visitTypeReferenceExpression(o);
        PsiReference reference = o.getReference();
        GoTypeReferenceExpression qualifier = o.getQualifier();
        PsiReference qualifierRef = qualifier != null ? qualifier.getReference() : null;
        PsiElement qualifierResolve = qualifierRef != null ? qualifierRef.resolve() : null;
        if (qualifier != null && qualifierResolve == null) return;
        if (reference == null || reference.resolve() == null) {
          PsiElement id = o.getIdentifier();
          String name = id.getText();
          problemsHolder.registerProblem(id, "Unresolved type " + "'" + name + "'", LIKE_UNKNOWN_SYMBOL);
        }
      }
    });
  }

  private static class GoGetPackageFix extends LocalQuickFixBase {
    private static final String TITLE = "Something went wrong with `go get`";
    @NotNull private final String myPackage;

    protected GoGetPackageFix(@NotNull String packageName) {
      super("Go get '" + packageName + "'");
      myPackage = packageName;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getPsiElement();
      final String path = getSdkPath(element);
      if (path == null) return;

      ProgressManager.getInstance().run(new Task.Modal(project, "Go get '" + myPackage + "'", true) {
        private OSProcessHandler myHandler;

        @Override
        public void onCancel() {
          if (myHandler != null) myHandler.destroyProcess();
        }

        public void run(@NotNull final ProgressIndicator indicator) {
          indicator.setIndeterminate(true);
          String executable = JpsGoSdkType.getGoExecutableFile(path).getAbsolutePath();

          GeneralCommandLine install = new GeneralCommandLine();
          install.setExePath(executable);
          install.addParameter("get");
          install.addParameter(myPackage);
          try {
            myHandler = new OSProcessHandler(install.createProcess(), install.getPreparedCommandLine(Platform.current()));
            final List<String> out = ContainerUtil.newArrayList();
            myHandler.addProcessListener(new ProcessAdapter() {
              @Override
              public void onTextAvailable(ProcessEvent event, Key outputType) {
                String text = event.getText();
                out.add(text);
                //indicator.setText2(text); // todo: look ugly
              }

              @Override
              public void processTerminated(ProcessEvent event) {
                int code = event.getExitCode();
                if (code == 0) return;
                String message = StringUtil.join(out.size() > 1 ? ContainerUtil.subList(out, 1) : out, "\n");
                Notifications.Bus.notify(new Notification("Go", TITLE, message, NotificationType.WARNING), project);
              }
            });
            ProcessTerminatedListener.attach(myHandler);
            myHandler.startNotify();
            myHandler.waitFor();
            indicator.setText2("Refreshing");
          }
          catch (ExecutionException e) {
            Notifications.Bus.notify(
              new Notification("Go", TITLE, StringUtil.notNullize(e.getMessage()), NotificationType.WARNING), project);
          }
          finally {
            LocalFileSystem.getInstance().refresh(false);
          }
        }
      });
    }
  }

  @Nullable
  private static String getSdkPath(@NotNull PsiElement element) {
    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    Sdk sdk = module == null ? null : ModuleRootManager.getInstance(module).getSdk();
    return sdk != null ? sdk.getHomePath() : null;
  }
}