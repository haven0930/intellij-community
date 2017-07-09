package com.jetbrains.python.psi.stubs;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StringStubIndexExtension;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyTargetExpression;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * @author Mikhail Golubev
 */
public class PyClassAttributesIndex extends StringStubIndexExtension<PyClass> {
  public static final StubIndexKey<String, PyClass> KEY = StubIndexKey.createIndexKey("Py.class.attributes");

  @NotNull
  @Override
  public StubIndexKey<String, PyClass> getKey() {
    return KEY;
  }

  public static Collection<PyClass> find(@NotNull String name, @NotNull Project project) {
    return StubIndex.getElements(KEY, name, project, GlobalSearchScope.allScope(project), PyClass.class);
  }

  public static Collection<PyTargetExpression> findClassAtrributes(@NotNull String name,
                                                                   @NotNull Project project,
                                                                   GlobalSearchScope scope) {
    return findAtttributes(name, project, scope, clazz -> clazz.findClassAttribute(name, false, null));
  }

  public static Collection<PyTargetExpression> findInstanceAttributes(@NotNull String name,
                                                                   @NotNull Project project,
                                                                   GlobalSearchScope scope) {
    return findAtttributes(name, project, scope, clazz -> clazz.findInstanceAttribute(name, false));
  }

  private static Collection<PyTargetExpression> findAtttributes(
    @NotNull String name,
    @NotNull Project project,
    GlobalSearchScope scope,
    Function<PyClass, PyTargetExpression> attrGetter) {
    List<PyTargetExpression> ret = new ArrayList<>();
    StubIndex.getInstance().processElements(KEY, name, project, scope, PyClass.class, clazz -> {
      ProgressManager.checkCanceled();
      PyTargetExpression attr = attrGetter.apply(clazz);
      if (attr != null) {
        ret.add(attr);
      }
      return true;
    });
    return ret;
  }


  /**
   * Returns all attributes: methods, class and instance fields that are declared directly in the specified class
   * (not taking inheritance into account).
   * <p/>
   * This method <b>must not</b> access the AST because it is being called during stub indexing.
   */
  @NotNull
  public static List<String> getAllDeclaredAttributeNames(@NotNull PyClass pyClass) {
    final List<PsiNamedElement> members = ContainerUtil.concat(pyClass.getInstanceAttributes(),
                                                               pyClass.getClassAttributes(),
                                                               Arrays.asList(pyClass.getMethods()));

    return ContainerUtil.mapNotNull(members, expression -> {
      final String attrName = expression.getName();
      return attrName != null ? attrName : null;
    });
  }
}
