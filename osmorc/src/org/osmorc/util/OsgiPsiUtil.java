package org.osmorc.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.lang.manifest.ManifestFileTypeFactory;
import org.jetbrains.lang.manifest.psi.Header;
import org.jetbrains.lang.manifest.psi.HeaderValue;
import org.jetbrains.lang.manifest.psi.ManifestFile;
import org.jetbrains.lang.manifest.psi.Section;

import java.util.List;

import static com.intellij.openapi.util.text.StringUtil.trimTrailing;

public class OsgiPsiUtil {
  private OsgiPsiUtil() { }

  @NotNull
  public static TextRange trimRange(@NotNull PsiElement element) {
    return trimRange(element, new TextRange(0, element.getTextLength()));
  }

  @NotNull
  public static TextRange trimRange(@NotNull PsiElement element, @NotNull TextRange range) {
    String text = element.getText();
    String substring = range.substring(text).trim();
    int start = text.indexOf(substring);
    int end = start + substring.length();
    return new TextRange(start, end);
  }

  public static boolean isHeader(@Nullable PsiElement element, @NotNull String headerName) {
    return element instanceof Header && headerName.equals(((Header)element).getName());
  }

  public static void setHeader(@NotNull ManifestFile manifestFile, @NotNull String headerName, @NotNull String headerValue) {
    Header header = manifestFile.getHeader(headerName);
    Header newHeader = createHeader(manifestFile.getProject(), headerName, headerValue);
    if (header != null) {
      header.replace(newHeader);
    }
    else {
      addHeader(manifestFile, newHeader);
    }
  }

  public static void appendToHeader(@NotNull ManifestFile manifestFile, @NotNull String headerName, @NotNull String headerValue) {
    Header header = manifestFile.getHeader(headerName);
    if (header != null) {
      HeaderValue oldValue = header.getHeaderValue();
      if (oldValue != null) {
        String oldText = trimTrailing(header.getText().substring(oldValue.getStartOffsetInParent(), header.getTextLength()));
        if (!oldText.isEmpty()) oldText += ",\n ";
        headerValue = oldText + headerValue;
      }
      header.replace(createHeader(manifestFile.getProject(), headerName, headerValue));
    }
    else {
      addHeader(manifestFile, createHeader(manifestFile.getProject(), headerName, headerValue));
    }
  }

  private static Header createHeader(Project project, String headerName, String valueText) {
    String text = String.format("%s: %s\n", headerName, valueText);
    PsiFile file = PsiFileFactory.getInstance(project).createFileFromText("DUMMY.MF", ManifestFileTypeFactory.MANIFEST, text);
    Header header = ((ManifestFile)file).getHeader(headerName);
    if (header == null) {
      throw new IncorrectOperationException("Bad header: '" + text + "'");
    }
    return header;
  }

  private static void addHeader(ManifestFile manifestFile, Header newHeader) {
    Section section = manifestFile.getMainSection();
    List<Header> headers = manifestFile.getHeaders();
    if (section == null) {
      manifestFile.add(newHeader.getParent());
    }
    else if (headers.isEmpty()) {
      section.addBefore(newHeader, section.getFirstChild());
    }
    else {
      section.addAfter(newHeader, headers.get(headers.size() - 1));
    }
  }
}
