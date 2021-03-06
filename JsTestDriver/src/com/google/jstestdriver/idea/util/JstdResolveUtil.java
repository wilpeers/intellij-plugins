package com.google.jstestdriver.idea.util;

import com.intellij.lang.javascript.index.JSNamedElementProxy;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.ResolveResult;
import org.jetbrains.annotations.NotNull;

/**
 * @author Sergey Simonchik
 */
public class JstdResolveUtil {
  private JstdResolveUtil() {}

  public static boolean isResolvedToFunction(@NotNull PsiPolyVariantReference psiPolyVariantReference) {
    ResolveResult[] resolveResults = psiPolyVariantReference.multiResolve(false);
    for (ResolveResult resolveResult : resolveResults) {
      boolean resolvedCorrectly = isResolveResultFunction(resolveResult);
      if (resolvedCorrectly) {
        return true;
      }
    }
    return false;
  }

  private static boolean isResolveResultFunction(@NotNull ResolveResult resolveResult) {
    PsiElement resolvedElement = resolveResult.getElement();
    if (resolvedElement == null || !resolveResult.isValidResult()) {
      return false;
    }
    if (resolvedElement instanceof JSNamedElementProxy) {
      JSNamedElementProxy proxy = (JSNamedElementProxy) resolvedElement;
      PsiElement element = proxy.getElement();
      return element != null && !(element instanceof PsiComment);
    }
    return !(resolvedElement instanceof PsiComment);
  }

}
