package com.goide.actions;

import com.goide.GoIcons;
import com.goide.GoNamesValidator;
import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.containers.ContainerUtil;

import java.util.Properties;

public class GoCreateFileAction extends CreateFileFromTemplateAction implements DumbAware { // todo: rewrite with package support?
  private static final String NEW_GO_FILE = "New Go File";
  private static final String PACKAGE = "PACKAGE";

  @Override
  protected PsiFile createFile(String name, String templateName, PsiDirectory dir) {
    FileTemplate template = FileTemplateManager.getInstance().getInternalTemplate(templateName);
    Properties properties = new Properties();
    properties.setProperty(PACKAGE, ContainerUtil.getLastItem(StringUtil.split(dir.getName(), "-")));
    try {
      PsiElement element = FileTemplateUtil.createFromTemplate(template, name, properties, dir);
      if (element instanceof PsiFile) return (PsiFile)element;
    }
    catch (Exception e) {
      LOG.warn(e);
      return null;
    }
    return super.createFile(name, templateName, dir);
  }

  public GoCreateFileAction() {
    super(NEW_GO_FILE, "", GoIcons.ICON);
  }

  @Override
  protected void buildDialog(final Project project, PsiDirectory directory, CreateFileFromTemplateDialog.Builder builder) {
    // todo: check that file already exists
    builder.
      setTitle(NEW_GO_FILE).
      addKind("Empty file", GoIcons.ICON, "Go File").
      addKind("Simple Application", GoIcons.ICON, "Go Application").
      setValidator(new InputValidatorEx() {
        @Override
        public boolean checkInput(String inputString) {
          return true;
        }

        @Override
        public boolean canClose(String inputString) {
          return !StringUtil.isEmptyOrSpaces(inputString) && getErrorText(inputString) == null;
        }

        @Override
        public String getErrorText(String inputString) {
          String error = " is not a valid Go file name";
          if (StringUtil.isEmpty(inputString)) return null;
          boolean ok = new GoNamesValidator().isIdentifier(inputString, project);
            if (ok && FileUtil.sanitizeFileName(inputString).equals(inputString)) {
              return null;
            }

          return "'" + inputString + "'" + error;
        }
      })
    ;
  }

  @Override
  protected String getActionName(PsiDirectory directory, String newName, String templateName) {
    return NEW_GO_FILE;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof GoCreateFileAction;
  }
}