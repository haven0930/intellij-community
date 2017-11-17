// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.psi.resolve;

import com.google.common.collect.Lists;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.QualifiedName;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.*;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.types.PyType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.jetbrains.python.psi.FutureFeature.ABSOLUTE_IMPORT;

/**
 * @author dcheryasov
 */
public class ResolveImportUtil {
  private ResolveImportUtil() {
  }

  private static final ThreadLocal<Set<String>> ourBeingImported = new ThreadLocal<Set<String>>() {
    @Override
    protected Set<String> initialValue() {
      return new HashSet<>();
    }
  };

  public static boolean isAbsoluteImportEnabledFor(PsiElement foothold) {
    if (foothold != null) {
      PsiFile file = foothold.getContainingFile();
      if (file instanceof PyFile) {
        final PyFile pyFile = (PyFile)file;
        if (pyFile.getLanguageLevel().isPy3K()) {
          return true;
        }
        return pyFile.hasImportFromFuture(ABSOLUTE_IMPORT);
      }
    }
    // if the relevant import is below the foothold, it is either legal or we've detected the offending statement already
    return false;
  }


  /**
   * Finds a directory that many levels above a given file, making sure that every level has an __init__.py.
   *
   * @param base  file that works as a reference.
   * @param depth must be positive, 1 means the dir that contains base, 2 is one dir above, etc.
   * @return found directory, or null.
   */
  @Nullable
  public static PsiDirectory stepBackFrom(PsiFile base, int depth) {
    if (depth == 0) {
      return base.getContainingDirectory();
    }
    PsiDirectory result;
    if (base != null) {
      base = base.getOriginalFile(); // just to make sure
      result = base.getContainingDirectory();
      int count = 1;
      while (result != null && PyUtil.isPackage(result, base)) {
        if (count >= depth) return result;
        result = result.getParentDirectory();
        count += 1;
      }
    }
    return null;
  }

  /**
   * @deprecated use {@link #multiResolveImportElement(PyImportElement, QualifiedName)} instead.
   */
  @Deprecated
  @Nullable
  public static PsiElement resolveImportElement(PyImportElement importElement, @NotNull final QualifiedName qName) {
    final List<RatedResolveResult> resultList = RatedResolveResult.sorted(multiResolveImportElement(importElement, qName));
    return resultList.size() > 0 ? resultList.get(0).getElement() : null;
  }

  @NotNull
  public static List<RatedResolveResult> multiResolveImportElement(PyImportElement importElement, @NotNull final QualifiedName qName) {
    PyUtil.verboseOnly(() -> PyPsiUtils.assertValid(importElement));
    final PyStatement importStatement = importElement.getContainingImportStatement();
    if (importStatement instanceof PyFromImportStatement) {
      return resolveNameInFromImport((PyFromImportStatement)importStatement, qName);
    }
    else {
      return resolveNameInImportStatement(importElement, qName);
    }
  }

  @NotNull
  public static List<RatedResolveResult> resolveNameInImportStatement(PyImportElement importElement, @NotNull QualifiedName qName) {
    final PsiFile file = importElement.getContainingFile().getOriginalFile();
    boolean absoluteImportEnabled = isAbsoluteImportEnabledFor(importElement);
    final List<PsiElement> modules = resolveModule(qName, file, absoluteImportEnabled, 0);
    return rateResults(modules);
  }

  @NotNull
  public static List<RatedResolveResult> resolveNameInFromImport(PyFromImportStatement importStatement, @NotNull QualifiedName qName) {
    PsiFile file = importStatement.getContainingFile().getOriginalFile();
    String name = qName.getComponents().get(0);

    final List<RatedResolveResult> results = new ArrayList<>();
    final List<PsiElement> candidates = importStatement.resolveImportSourceCandidates();
    for (PsiElement candidate : candidates) {
      if (!candidate.isValid()) {
        throw new PsiInvalidElementAccessException(candidate, "Got an invalid candidate from resolveImportSourceCandidates(): " +
                                                              candidate.getClass());
      }
      if (candidate instanceof PsiDirectory) {
        candidate = PyUtil.getPackageElement((PsiDirectory)candidate, importStatement);
      }
      results.addAll(updateRatedResults(resolveChildren(candidate, name, file, false, true, false, false)));
    }
    return results;
  }

  @NotNull
  public static List<PsiElement> resolveFromImportStatementSource(@NotNull PyFromImportStatement fromImportStatement,
                                                                  @Nullable QualifiedName qName) {
    final boolean absoluteImportEnabled = isAbsoluteImportEnabledFor(fromImportStatement);
    final PsiFile file = fromImportStatement.getContainingFile();
    return resolveModule(qName, file, absoluteImportEnabled, fromImportStatement.getRelativeLevel());
  }

  /**
   * Resolves a module reference in a general case.
   *
   *
   * @param qualifiedName    qualified name of the module reference to resolve
   * @param sourceFile       where that reference resides; serves as PSI foothold to determine module, project, etc.
   * @param importIsAbsolute if false, try old python 2.x's "relative first, absolute next" approach.
   * @param relativeLevel    if > 0, step back from sourceFile and resolve from there (even if importIsAbsolute is false!).
   * @return list of possible candidates
   */
  @NotNull
  public static List<PsiElement> resolveModule(@Nullable QualifiedName qualifiedName, @Nullable PsiFile sourceFile,
                                               boolean importIsAbsolute, int relativeLevel) {
    if (qualifiedName == null || sourceFile == null) {
      return Collections.emptyList();
    }
    final ResolveModuleParams params = new ResolveModuleParams(qualifiedName, sourceFile, importIsAbsolute, relativeLevel);
    return PyUtil.getParameterizedCachedValue(sourceFile, params, ResolveImportUtil::calculateResolveModule);
  }

  @NotNull
  private static List<PsiElement> calculateResolveModule(@NotNull ResolveModuleParams params) {
    final QualifiedName qualifiedName = params.getName();
    final int relativeLevel = params.getLevel();
    final PsiFile sourceFile = params.getFile();
    final boolean importIsAbsolute = params.isAbsolute();

    final String marker = qualifiedName + "#" + Integer.toString(relativeLevel);
    final Set<String> beingImported = ourBeingImported.get();
    if (beingImported.contains(marker)) {
      return Collections.emptyList(); // break endless loop in import
    }
    try {
      beingImported.add(marker);
      final PyQualifiedNameResolveContext initialContext = PyResolveImportUtil.fromFoothold(sourceFile);
      final PyQualifiedNameResolveContext context = relativeLevel > 0 ?
                                                    initialContext.copyWithRelative(relativeLevel).copyWithoutRoots() :
                                                    importIsAbsolute ? initialContext : initialContext.copyWithRelative(0);
      return PyResolveImportUtil.resolveQualifiedName(qualifiedName, context);
    }
    finally {
      beingImported.remove(marker);
    }
  }

  @NotNull
  public static List<PsiElement> multiResolveModuleInRoots(@NotNull QualifiedName moduleQualifiedName, @Nullable PsiElement foothold) {
    if (foothold == null) return Collections.emptyList();
    return PyResolveImportUtil.resolveQualifiedName(moduleQualifiedName,
                                                                              PyResolveImportUtil.fromFoothold(foothold));
  }

  /**
   * @deprecated {@link use {@link #multiResolveModuleInRoots(QualifiedName, PsiElement)}}
   */
  @Deprecated
  @Nullable
  public static PsiElement resolveModuleInRoots(@NotNull QualifiedName moduleQualifiedName, @Nullable PsiElement foothold) {
    final List<PsiElement> results = multiResolveModuleInRoots(moduleQualifiedName, foothold);
    if (results.isEmpty()) {
      return null;
    }
    return results.get(0);
  }


  @Nullable
  static PythonPathCache getPathCache(PsiElement foothold) {
    PythonPathCache cache = null;
    final Module module = ModuleUtilCore.findModuleForPsiElement(foothold);
    if (module != null) {
      cache = PythonModulePathCache.getInstance(module);
    }
    else {
      final Sdk sdk = PyBuiltinCache.findSdkForFile(foothold.getContainingFile());
      if (sdk != null) {
        cache = PythonSdkPathCache.getInstance(foothold.getProject(), sdk);
      }
    }
    return cache;
  }

  /**
   * @deprecated Use {@link #resolveChildren(PsiElement, String, PsiFile, boolean, boolean, boolean, boolean)} instead.
   */
  @Deprecated
  @Nullable
  public static PsiElement resolveChild(@Nullable final PsiElement parent,
                                        @NotNull final String referencedName,
                                        @Nullable final PsiFile containingFile,
                                        boolean fileOnly,
                                        boolean checkForPackage,
                                        boolean withoutStubs) {
    final List<RatedResolveResult> results = resolveChildren(parent, referencedName, containingFile, fileOnly, checkForPackage,
                                                             withoutStubs, false);
    return results.isEmpty() ? null : RatedResolveResult.sorted(results).get(0).getElement();
  }

  /**
   * Tries to find referencedName under the parent element.
   *
   * @param parent          element under which to look for referenced name; if null, null is returned.
   * @param referencedName  which name to look for.
   * @param containingFile  where we're in.
   * @param fileOnly        if true, considers only a PsiFile child as a valid result; non-file hits are ignored.
   * @param checkForPackage if true, directories are returned only if they contain __init__.py
   * @param withoutStubs
   * @return the element the referencedName resolves to, or null.
   */
  @NotNull
  public static List<RatedResolveResult> resolveChildren(@Nullable PsiElement parent,
                                                         @NotNull String referencedName,
                                                         @Nullable PsiFile containingFile,
                                                         boolean fileOnly,
                                                         boolean checkForPackage,
                                                         boolean withoutStubs,
                                                         boolean withoutForeign) {
    if (parent == null) {
      return Collections.emptyList();
    }
    else if (parent instanceof PyFile) {
      return resolveInPackageModule((PyFile)parent, referencedName, containingFile, fileOnly, checkForPackage, withoutStubs,
                                    withoutForeign);
    }
    else if (parent instanceof PsiDirectory) {
      return resolveInPackageDirectory(parent, referencedName, containingFile, fileOnly, checkForPackage, withoutStubs, withoutForeign);
    }
    else {
      return resolveMemberFromReferenceTypeProviders(parent, referencedName);
    }
  }

  @NotNull
  private static List<RatedResolveResult> resolveInPackageModule(@NotNull PyFile parent, @NotNull String referencedName,
                                                                 @Nullable PsiFile containingFile, boolean fileOnly,
                                                                 boolean checkForPackage, boolean withoutStubs, boolean withoutForeign) {
    final List<RatedResolveResult> moduleMembers = resolveModuleMember(parent, referencedName);
    final List<RatedResolveResult> resolvedInModule = Lists.newArrayList();
    final List<RatedResolveResult> results = Lists.newArrayList();
    for (RatedResolveResult member : moduleMembers) {
      final PsiElement moduleMember = member.getElement();
      if (!fileOnly || PsiTreeUtil.instanceOf(moduleMember, PsiFile.class, PsiDirectory.class)) {
        results.add(member);
        if (moduleMember != null && !preferResolveInDirectoryOverModule(moduleMember)) {
          resolvedInModule.add(member);
        }
      }
    }
    if (!resolvedInModule.isEmpty()) {
      return resolvedInModule;
    }

    final List<RatedResolveResult> resolvedInDirectory = resolveInPackageDirectory(parent, referencedName, containingFile, fileOnly,
                                                                                   checkForPackage, withoutStubs, withoutForeign);
    if (!resolvedInDirectory.isEmpty()) {
      return resolvedInDirectory;
    }

    return results;
  }

  private static boolean preferResolveInDirectoryOverModule(@NotNull PsiElement resolved) {
    return PsiTreeUtil.getStubOrPsiParentOfType(resolved, PyExceptPart.class) != null ||
           PsiTreeUtil.instanceOf(resolved, PsiFile.class, PsiDirectory.class) ||  // XXX: Workaround for PY-9439
           isDunderAll(resolved);
  }

  @NotNull
  private static List<RatedResolveResult> resolveModuleMember(@NotNull PyFile file, @NotNull String referencedName) {
    final PyModuleType moduleType = new PyModuleType(file);
    final PyResolveContext resolveContext = PyResolveContext.defaultContext();
    final List<? extends RatedResolveResult> results = moduleType.resolveMember(referencedName, null, AccessDirection.READ,
                                                                                resolveContext);
    if (results == null) {
      return Collections.emptyList();
    }
    return Lists.newArrayList(results);
  }

  @NotNull
  private static List<RatedResolveResult> resolveInPackageDirectory(@Nullable PsiElement parent, @NotNull String referencedName,
                                                                    @Nullable PsiFile containingFile, boolean fileOnly,
                                                                    boolean checkForPackage, boolean withoutStubs, boolean withoutForeign) {
    final PsiElement parentDir = PyUtil.turnInitIntoDir(parent);
    if (parentDir instanceof PsiDirectory) {
      final List<RatedResolveResult> resolved = resolveInDirectory(referencedName, containingFile, (PsiDirectory)parentDir, fileOnly,
                                                                   checkForPackage, withoutStubs);
      if (!resolved.isEmpty()) {
        for (RatedResolveResult result : resolved) {
          if (result.getRate() > RatedResolveResult.RATE_LOW) {
            return resolved;
          }
        }
      }
      if (!withoutForeign && parent instanceof PsiFile) {
        final PsiElement foreign = resolveForeignImports((PsiFile)parent, referencedName);
        if (foreign != null) {
          final ResolveResultList results = new ResolveResultList();
          results.addAll(resolved);
          results.poke(foreign, RatedResolveResult.RATE_NORMAL);
          return results;
        }
      }
      return resolved;
    }
    return Collections.emptyList();
  }

  @Nullable
  private static PsiElement resolveForeignImports(@NotNull PsiFile foothold, @NotNull String referencedName) {
    final PyQualifiedNameResolveContext context = PyResolveImportUtil.fromFoothold(foothold).copyWithoutRoots();
    final List<PsiElement> results = PyResolveImportUtil.resolveQualifiedName(QualifiedName.fromDottedString(referencedName), context);
    return !results.isEmpty() ? results.get(0) : null;
  }

  @NotNull
  private static List<RatedResolveResult> resolveMemberFromReferenceTypeProviders(@NotNull PsiElement parent,
                                                                                  @NotNull String referencedName) {
    final PyResolveContext resolveContext = PyResolveContext.defaultContext();
    final PyType refType = PyReferenceExpressionImpl.getReferenceTypeFromProviders(parent, resolveContext.getTypeEvalContext(), null);
    if (refType != null) {
      final List<? extends RatedResolveResult> result = refType.resolveMember(referencedName, null, AccessDirection.READ, resolveContext);
      if (result != null) {
        return Lists.newArrayList(result);
      }
    }
    return Collections.emptyList();
  }

  private static boolean isDunderAll(@NotNull PsiElement element) {
    return (element instanceof PyElement) && PyNames.ALL.equals(((PyElement)element).getName());
  }

  @NotNull
  private static List<RatedResolveResult> resolveInDirectory(@NotNull final String referencedName,
                                                             @Nullable final PsiFile containingFile,
                                                             final PsiDirectory dir,
                                                             boolean isFileOnly,
                                                             boolean checkForPackage,
                                                             boolean withoutStubs) {
    final PsiDirectory subdir = dir.findSubdirectory(referencedName);
    // VFS may be case insensitive on Windows, but resolve is always case sensitive (PEP 235, PY-18958), so we check name here
    if (subdir != null && (!checkForPackage || PyUtil.isPackage(subdir, containingFile)) && subdir.getName().equals(referencedName)) {
      return ResolveResultList.to(subdir);
    }

    final PsiFile module = findPyFileInDir(dir, referencedName, withoutStubs);
    if (module != null) {
      return ResolveResultList.to(module);
    }

    if (!isFileOnly) {
      final PsiElement packageElement = PyUtil.getPackageElement(dir, containingFile);
      if (packageElement == containingFile) {
        return Collections.emptyList(); // don't dive into the file we're in
      }
      if (packageElement instanceof PyFile) {
        return ((PyFile)packageElement).multiResolveName(referencedName);
      }
    }
    return Collections.emptyList();
  }

  @Nullable
  private static PsiFile findPyFileInDir(PsiDirectory dir, String referencedName, boolean withoutStubs) {
    PsiFile file = null;
    if (!withoutStubs) {
      file = dir.findFile(referencedName + PyNames.DOT_PYI);
    }
    if (file == null) {
      file = dir.findFile(referencedName + PyNames.DOT_PY);
    }
    if (file == null) {
      final List<FileNameMatcher> associations = FileTypeManager.getInstance().getAssociations(PythonFileType.INSTANCE);
      for (FileNameMatcher association : associations) {
        if (association instanceof ExtensionFileNameMatcher) {
          file = dir.findFile(referencedName + "." + ((ExtensionFileNameMatcher)association).getExtension());
          if (file != null) break;
        }
      }
    }
    if (file != null && FileUtil.getNameWithoutExtension(file.getName()).equals(referencedName)) {
      return file;
    }
    return null;
  }

  public static ResolveResultList rateResults(List<? extends PsiElement> targets) {
    ResolveResultList ret = new ResolveResultList();
    for (PsiElement target : targets) {
      if (target instanceof PsiDirectory) {
        target = PyUtil.getPackageElement((PsiDirectory)target, null);
      }
      if (target != null) {
        int rate = RatedResolveResult.RATE_HIGH;
        if (target instanceof PyFile) {
          for (PyResolveResultRater rater : Extensions.getExtensions(PyResolveResultRater.EP_NAME)) {
            rate += rater.getImportElementRate(target);
          }
        }
        else if (isDunderAll(target)) {
          rate = RatedResolveResult.RATE_NORMAL;
        }
        ret.poke(target, rate);
      }
    }
    return ret;
  }

  @NotNull
  private static List<RatedResolveResult> updateRatedResults(@NotNull List<? extends RatedResolveResult> results) {
    if (results.isEmpty()) return Collections.emptyList();
    final ResolveResultList result = new ResolveResultList();

    for (RatedResolveResult resolveResult : results) {
      PsiElement element = resolveResult.getElement();
      if (element instanceof PsiDirectory) {
        element = PyUtil.getPackageElement((PsiDirectory)element, element);
      }

      if (element != null) {
        int delta = 0;
        for (PyResolveResultRater rater : Extensions.getExtensions(PyResolveResultRater.EP_NAME)) {
          delta += rater.getImportElementRate(element);
        }

        result.poke(element, resolveResult.getRate() + delta);
      }
    }

    return result;
  }

  /**
   * @param element what we test (identifier, reference, import element, etc)
   * @return the how the element relates to an enclosing import statement, if any
   * @see PointInImport
   */
  @NotNull
  public static PointInImport getPointInImport(@NotNull PsiElement element) {
    final PsiElement parent = PsiTreeUtil.getNonStrictParentOfType(element, PyImportElement.class, PyFromImportStatement.class);
    if (parent instanceof PyFromImportStatement) {
      return PointInImport.AS_MODULE; // from foo ...
    }
    if (parent instanceof PyImportElement) {
      final PsiElement statement = parent.getParent();
      if (statement instanceof PyImportStatement) {
        return PointInImport.AS_MODULE; // import foo,...
      }
      else if (statement instanceof PyFromImportStatement) {
        return PointInImport.AS_NAME;
      }
    }
    return PointInImport.NONE;
  }

  private static final class ResolveModuleParams {
    @NotNull private final QualifiedName myName;
    @NotNull private final PsiFile myFile;
    private final boolean myAbsolute;
    private final int myLevel;

    public ResolveModuleParams(@NotNull QualifiedName qualifiedName, @NotNull PsiFile file, boolean importIsAbsolute, int relativeLevel) {
      myName = qualifiedName;
      myFile = file;
      myAbsolute = importIsAbsolute;
      myLevel = relativeLevel;
    }

    @NotNull
    public QualifiedName getName() {
      return myName;
    }

    public boolean isAbsolute() {
      return myAbsolute;
    }

    public int getLevel() {
      return myLevel;
    }

    @NotNull
    public PsiFile getFile() {
      return myFile;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ResolveModuleParams params = (ResolveModuleParams)o;

      if (myAbsolute != params.myAbsolute) return false;
      if (myLevel != params.myLevel) return false;
      if (!myName.equals(params.myName)) return false;
      if (!myFile.equals(params.myFile)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = myName.hashCode();
      result = 31 * result + myFile.hashCode();
      result = 31 * result + (myAbsolute ? 1 : 0);
      result = 31 * result + myLevel;
      return result;
    }
  }
}
