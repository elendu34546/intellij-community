/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.groovy.lang.psi.impl.synthetic;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightTypeParameterListBuilder;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.extensions.NamedArgumentDescriptor;
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocComment;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifier;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotation;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrCodeBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrOpenBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameterList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrReflectedMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Max Medvedev
 */
public class GrReflectedMethodImpl extends LightMethodBuilder implements GrReflectedMethod {
  private static final Logger LOG = Logger.getInstance(GrReflectedMethodImpl.class);
  @NonNls public static final String CATEGORY_PARAMETER_NAME = "self";

  private final GrMethod myBaseMethod;
  private GrParameter[] mySkippedParameters = null;

  public GrReflectedMethodImpl(GrMethod baseMethod, int optionalParams, PsiClassType categoryType) {
    super(baseMethod.getManager(), baseMethod.getLanguage(), baseMethod.getName(),
          new GrLightParameterListBuilder(baseMethod.getManager(), baseMethod.getLanguage()),
          new GrLightModifierList(baseMethod), new LightReferenceList(baseMethod.getManager()),  
          new LightTypeParameterListBuilder(baseMethod.getManager(), baseMethod.getLanguage())
    );
    
    initParameterList(baseMethod, optionalParams, categoryType);
    initTypeParameterList(baseMethod);
    initModifiers(baseMethod, categoryType != null);
    initThrowsList(baseMethod);
    setContainingClass(baseMethod.getContainingClass());
    setMethodReturnType(baseMethod.getReturnType());
    setConstructor(baseMethod.isConstructor());

    myBaseMethod = baseMethod;
  }

  private void initTypeParameterList(GrMethod method) {
    for (PsiTypeParameter parameter : method.getTypeParameters()) {
      addTypeParameter(parameter);
    }
  }

  private void initThrowsList(GrMethod baseMethod) {
    for (PsiClassType exception : baseMethod.getThrowsList().getReferencedTypes()) {
      addException(exception);
    }
  }

  private void initModifiers(GrMethod baseMethod, boolean isCategoryMethod) {
    final GrLightModifierList myModifierList = ((GrLightModifierList)getModifierList());

    for (String modifier : GrModifier.GROOVY_MODIFIERS) {
      if (baseMethod.hasModifierProperty(modifier)) {
        myModifierList.addModifier(modifier);
      }
    }
    
    for (PsiElement modifier : baseMethod.getModifierList().getModifiers()) {
      if (modifier instanceof GrAnnotation) {
        final String qualifiedName = ((GrAnnotation)modifier).getQualifiedName();
        if (qualifiedName != null) {
          myModifierList.addAnnotation(qualifiedName);
        }
        else {
          myModifierList.addAnnotation(((GrAnnotation)modifier).getShortName());
        }
      }
    }

    if (isCategoryMethod) {
      myModifierList.addModifier(PsiModifier.STATIC);
    }
  }

  private void initParameterList(GrMethod baseMethod, int optionalParams, PsiClassType categoryType) {
    final GrParameter[] parameters = baseMethod.getParameters();
    final GrLightParameterListBuilder parameterList = (GrLightParameterListBuilder)getParameterList();

    List<GrParameter> skipped = new ArrayList<GrParameter>();

    if (categoryType != null) {
      parameterList.addParameter(new GrLightParameter(CATEGORY_PARAMETER_NAME, categoryType, this));
    }

    for (GrParameter parameter : parameters) {
      if (parameter.isOptional()) {
        if (optionalParams < 1) {
          skipped.add(parameter);
          continue;
        }
        optionalParams--;
      }
      parameterList.addParameter(new GrLightParameter(parameter.getName(), parameter.getType(), this));
    }

    LOG.assertTrue(optionalParams == 0, optionalParams + "methodText: " + baseMethod.getText());

    mySkippedParameters = skipped.toArray(new GrParameter[skipped.size()]);
  }

  @NotNull
  @Override
  public GrMethod getBaseMethod() {
    return myBaseMethod;
  }

  @NotNull
  @Override
  public GrParameter[] getSkippedParameters() {
    return mySkippedParameters;
  }

  @NotNull
  @Override
  public PsiElement getNavigationElement() {
    return myBaseMethod.getNavigationElement();
  }

  @Override
  public GrOpenBlock getBlock() {
    return myBaseMethod.getBlock();
  }

  @Override
  public void setBlock(GrCodeBlock newBlock) {
    throw new UnsupportedOperationException("synthetic method!");
  }

  @Override
  public GrTypeElement getReturnTypeElementGroovy() {
    return myBaseMethod.getReturnTypeElementGroovy();
  }

  @Override
  public PsiType getInferredReturnType() {
    return myBaseMethod.getInferredReturnType();
  }

  @Override
  public GrTypeElement setReturnType(@Nullable PsiType newReturnType) {
    throw new UnsupportedOperationException("synthetic method!");
  }

  @NotNull
  @Override
  public Map<String, NamedArgumentDescriptor> getNamedParameters() {
    return myBaseMethod.getNamedParameters();
  }

  @NotNull
  @Override
  public GrReflectedMethod[] getReflectedMethods() {
    return GrReflectedMethod.EMPTY_ARRAY;
  }

  @Override
  public GrMember[] getMembers() {
    return myBaseMethod.getMembers();
  }

  @NotNull
  @Override
  public PsiElement getNameIdentifierGroovy() {
    return myBaseMethod.getNameIdentifierGroovy();
  }

  @Override
  public GrParameter[] getParameters() {
    return getParameterList().getParameters();
  }

  @NotNull
  @Override
  public GrParameterList getParameterList() {
    return (GrParameterList)super.getParameterList();
  }

  @Override
  public void accept(GroovyElementVisitor visitor) {
    visitor.visitMethod(this);
  }

  @Override
  public void acceptChildren(GroovyElementVisitor visitor) {
    //todo
  }

  @Override
  public GrDocComment getDocComment() {
    return myBaseMethod.getDocComment();
  }

  @Override
  public String toString() {
    return "reflected method";
  }

  @NotNull
  @Override
  public GrModifierList getModifierList() {
    return (GrModifierList)super.getModifierList();
  }

  @Override
  public Icon getIcon(int flags) {
    return myBaseMethod.getIcon(flags);
  }

  @Override
  public PsiIdentifier getNameIdentifier() {
    return PsiUtil.getJavaNameIdentifier(myBaseMethod);
  }

  @Override
  public boolean isPhysical() {
    return myBaseMethod.isPhysical();
  }

  public static GrReflectedMethod[] createReflectedMethods(GrMethod method) {
    final PsiClassType categoryType = method.hasModifierProperty(PsiModifier.STATIC) ? null : getCategoryType(method);

    final GrParameter[] parameters = method.getParameters();
    int count = 0;
    for (GrParameter parameter : parameters) {
      if (parameter.isOptional()) count++;
    }

    if (count == 0 && categoryType == null) return GrReflectedMethod.EMPTY_ARRAY;

    final GrReflectedMethod[] methods = new GrReflectedMethod[count + 1];
    for (int i = 0; i <= count; i++) {
      methods[i] = new GrReflectedMethodImpl(method, count - i, categoryType);
    }

    return methods;
  }

  @Nullable
  private static PsiClassType getCategoryType(GrMethod method) {
    final PsiClass containingClass = method.getContainingClass();
    if (containingClass == null) return null;
    return CachedValuesManager.getManager(method.getProject()).getCachedValue(containingClass, new CachedValueProvider<PsiClassType>() {
      @Override
      public Result<PsiClassType> compute() {
        return Result.create(inferCategoryType(containingClass), PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT);
      }

      @Nullable
      private PsiClassType inferCategoryType(final PsiClass aClass) {
        return RecursionManager.doPreventingRecursion(aClass, true, new Computable<PsiClassType>() {
          @Nullable
          @Override
          public PsiClassType compute() {
            final PsiModifierList modifierList = aClass.getModifierList();
            if (modifierList == null) return null;

            final PsiAnnotation annotation = modifierList.findAnnotation(GroovyCommonClassNames.GROOVY_LANG_CATEGORY);
            if (annotation == null) return null;

            PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
            if (!(value instanceof GrReferenceExpression)) return null;

            if ("class".equals(((GrReferenceExpression)value).getReferenceName())) value = ((GrReferenceExpression)value).getQualifier();
            if (!(value instanceof GrReferenceExpression)) return null;

            final PsiElement resolved = ((GrReferenceExpression)value).resolve();
            if (!(resolved instanceof PsiClass)) return null;

            String className = ((PsiClass)resolved).getQualifiedName();
            if (className == null) className = ((PsiClass)resolved).getName();
            if (className == null) return null;

            return JavaPsiFacade.getElementFactory(aClass.getProject()).createTypeByFQClassName(className, resolved.getResolveScope());
          }
        });
      }
    });
  }

  @NotNull
  @Override
  public PsiElement getPrototype() {
    return getBaseMethod();
  }
}
