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

package org.jetbrains.plugins.groovy.formatter;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.formatter.FormattingDocumentModelImpl;
import com.intellij.psi.formatter.PsiBasedFormattingModel;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.containers.CollectionFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.lexer.TokenSets;

import java.util.Map;

/**
 * @author ilyas
 */
public class GroovyFormattingModelBuilder implements FormattingModelBuilder {
  @NotNull
  public FormattingModel createModel(final PsiElement element, final CodeStyleSettings settings) {
    ASTNode node = element.getNode();
    assert node != null;
    PsiFile containingFile = element.getContainingFile().getViewProvider().getPsi(GroovyFileType.GROOVY_LANGUAGE);
    assert containingFile != null : element.getContainingFile();
    ASTNode astNode = containingFile.getNode();
    assert astNode != null;
    CommonCodeStyleSettings groovySettings = settings.getCommonSettings(GroovyFileType.GROOVY_LANGUAGE);
    GroovyCodeStyleSettings customSettings = settings.getCustomSettings(GroovyCodeStyleSettings.class);

    final Map<PsiElement, Alignment> innerAlignments = CollectionFactory.hashMap();
    if (customSettings.USE_FLYING_GEESE_BRACES) {
      element.accept(new PsiRecursiveElementVisitor() {
        @Override
        public void visitElement(PsiElement element) {
          if (GeeseUtil.isClosureRBrace(element)) {
            final Alignment alignment = GeeseUtil.calculateRBraceAlignment(element, innerAlignments);
            innerAlignments.put(element, alignment);
          }
          else {
            super.visitElement(element);
          }
        }
      });
    }
    final GroovyBlock block =
      new GroovyBlock(astNode, null, Indent.getAbsoluteNoneIndent(), null, groovySettings, customSettings, innerAlignments);
    return new GroovyFormattingModel(containingFile, block, FormattingDocumentModelImpl.createOn(containingFile));
  }

  @Nullable
  public TextRange getRangeAffectingIndent(PsiFile file, int offset, ASTNode elementAtOffset) {
    return null;
  }

  /**
   * Standard {@link PsiBasedFormattingModel} extension that handles the fact that groovy uses not single white space token type
   * ({@link TokenType#WHITE_SPACE}) but one additional token type as well: {@link GroovyTokenTypes#mNLS}. So, it allows to adjust
   * white space token type to use for calling existing common formatting stuff.
   */
  private static class GroovyFormattingModel extends PsiBasedFormattingModel {

    GroovyFormattingModel(PsiFile file, @NotNull Block rootBlock, FormattingDocumentModelImpl documentModel) {
      super(file, rootBlock, documentModel);
    }

    @Override
    protected String replaceWithPsiInLeaf(TextRange textRange, String whiteSpace, ASTNode leafElement) {
      if (!myCanModifyAllWhiteSpaces) {
        if (TokenSets.WHITE_SPACES_SET.contains(leafElement.getElementType())) return null;
      }

      IElementType elementTypeToUse = TokenType.WHITE_SPACE;
      ASTNode prevNode = TreeUtil.prevLeaf(leafElement);
      if (prevNode != null && TokenSets.WHITE_SPACES_SET.contains(prevNode.getElementType())) {
        elementTypeToUse = prevNode.getElementType();
      }
      FormatterUtil.replaceWhiteSpace(whiteSpace, leafElement, elementTypeToUse, textRange);
      return whiteSpace;
    }
  }
}
