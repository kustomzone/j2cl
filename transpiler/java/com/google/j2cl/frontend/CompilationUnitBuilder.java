/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.frontend;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.j2cl.ast.AssertStatement;
import com.google.j2cl.ast.BinaryExpression;
import com.google.j2cl.ast.BinaryOperator;
import com.google.j2cl.ast.Block;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.ExpressionStatement;
import com.google.j2cl.ast.Field;
import com.google.j2cl.ast.InstanceOfExpression;
import com.google.j2cl.ast.JavaType;
import com.google.j2cl.ast.JavaType.Kind;
import com.google.j2cl.ast.Method;
import com.google.j2cl.ast.MethodReference;
import com.google.j2cl.ast.NewArray;
import com.google.j2cl.ast.NewInstance;
import com.google.j2cl.ast.NullLiteral;
import com.google.j2cl.ast.NumberLiteral;
import com.google.j2cl.ast.ParenthesizedExpression;
import com.google.j2cl.ast.PostfixExpression;
import com.google.j2cl.ast.PrefixExpression;
import com.google.j2cl.ast.Statement;
import com.google.j2cl.ast.TypeReference;
import com.google.j2cl.ast.Variable;
import com.google.j2cl.ast.VariableDeclaration;
import com.google.j2cl.ast.VariableReference;
import com.google.j2cl.errors.Errors;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Creates a J2CL Java AST from the AST provided by JDT.
 */
public class CompilationUnitBuilder {

  private class ASTConverter {
    private Map<IVariableBinding, Variable> variableByJdtBinding = new HashMap<>();

    private CompilationUnit convert(
        String sourceFilePath, org.eclipse.jdt.core.dom.CompilationUnit jdtCompilationUnit) {
      String packageName = JdtUtils.getCompilationUnitPackageName(jdtCompilationUnit);
      CompilationUnit j2clCompilationUnit = new CompilationUnit(sourceFilePath, packageName);
      for (Object object : jdtCompilationUnit.types()) {
        AbstractTypeDeclaration abstractTypeDeclaration = (AbstractTypeDeclaration) object;
        j2clCompilationUnit.addType(convert(abstractTypeDeclaration));
      }
      return j2clCompilationUnit;
    }

    private JavaType convert(AbstractTypeDeclaration node) {
      switch (node.getNodeType()) {
        case ASTNode.TYPE_DECLARATION:
          return convert((TypeDeclaration) node);
        default:
          throw new RuntimeException(
              "Need to implement translation for AbstractTypeDeclaration type: "
                  + node.getClass().getName());
      }
    }

    private JavaType convert(TypeDeclaration node) {
      ITypeBinding typeBinding = node.resolveBinding();
      JavaType type = createJavaType(typeBinding);
      for (Object object : node.bodyDeclarations()) {
        if (object instanceof FieldDeclaration) {
          FieldDeclaration fieldDeclaration = (FieldDeclaration) object;
          type.addFields(convert(fieldDeclaration));
        } else if (object instanceof MethodDeclaration) {
          MethodDeclaration methodDeclaration = (MethodDeclaration) object;
          type.addMethod(convert(methodDeclaration));
        } else {
          throw new RuntimeException(
              "Need to implement translation for BodyDeclaration type: "
                  + node.getClass().getName());
        }
      }
      return type;
    }

    private List<Field> convert(FieldDeclaration node) {
      List<Field> fields = new ArrayList<>();
      for (Object object : node.fragments()) {
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) object;
        Expression initializer =
            fragment.getInitializer() == null ? null : convert(fragment.getInitializer());
        fields.add(
            new Field(
                JdtUtils.createFieldReference(
                    fragment.resolveBinding(), compilationUnitNameLocator),
                initializer));
      }
      return fields;
    }

    private Method convert(MethodDeclaration node) {
      List<Variable> parameters = new ArrayList<>();
      for (Object element : node.parameters()) {
        SingleVariableDeclaration parameter = (SingleVariableDeclaration) element;
        parameters.add(
            JdtUtils.createVariable(parameter.resolveBinding(), compilationUnitNameLocator));
      }
      return new Method(
          JdtUtils.createMethodReference(node.resolveBinding(), compilationUnitNameLocator),
          parameters,
          convert(node.getBody()));
    }

    @SuppressWarnings("cast")
    private NewArray convert(org.eclipse.jdt.core.dom.ArrayCreation node) {
      ArrayType arrayType = node.getType();

      @SuppressWarnings("unchecked")
      List<Expression> dimensionExpressions =
          convert((List<org.eclipse.jdt.core.dom.Expression>) node.dimensions());
      // If some dimensions are not initialized then make that explicit.
      while (dimensionExpressions.size() < arrayType.getDimensions()) {
        dimensionExpressions.add(new NullLiteral());
      }

      ITypeBinding leafTypeBinding = arrayType.getElementType().resolveBinding();
      return new NewArray(
          dimensionExpressions,
          JdtUtils.createTypeReference(leafTypeBinding, compilationUnitNameLocator));
    }

    private NewInstance convert(org.eclipse.jdt.core.dom.ClassInstanceCreation node) {
      Expression qualifier = node.getExpression() == null ? null : convert(node.getExpression());
      MethodReference constructor =
          JdtUtils.createMethodReference(
              node.resolveConstructorBinding(), compilationUnitNameLocator);
      List<Expression> arguments = new ArrayList<>();
      for (Object argument : node.arguments()) {
        arguments.add(convert((org.eclipse.jdt.core.dom.Expression) argument));
      }
      return new NewInstance(qualifier, constructor, arguments);
    }

    private Expression convert(org.eclipse.jdt.core.dom.Expression node) {
      switch (node.getNodeType()) {
        case ASTNode.ARRAY_CREATION:
          return convert((org.eclipse.jdt.core.dom.ArrayCreation) node);
        case ASTNode.CLASS_INSTANCE_CREATION:
          return convert((org.eclipse.jdt.core.dom.ClassInstanceCreation) node);
        case ASTNode.INFIX_EXPRESSION:
          return convert((org.eclipse.jdt.core.dom.InfixExpression) node);
        case ASTNode.INSTANCEOF_EXPRESSION:
          return convert((org.eclipse.jdt.core.dom.InstanceofExpression) node);
        case ASTNode.NUMBER_LITERAL:
          return convert((org.eclipse.jdt.core.dom.NumberLiteral) node);
        case ASTNode.PARENTHESIZED_EXPRESSION:
          return convert((org.eclipse.jdt.core.dom.ParenthesizedExpression) node);
        case ASTNode.POSTFIX_EXPRESSION:
          return convert((org.eclipse.jdt.core.dom.PostfixExpression) node);
        case ASTNode.PREFIX_EXPRESSION:
          return convert((org.eclipse.jdt.core.dom.PrefixExpression) node);
        case ASTNode.SIMPLE_NAME:
          return convert((org.eclipse.jdt.core.dom.SimpleName) node);
        default:
          throw new RuntimeException(
              "Need to implement translation for expression type: " + node.getClass().getName());
      }
    }

    private List<Expression> convert(List<org.eclipse.jdt.core.dom.Expression> nodes) {
      return new ArrayList<>(
          Lists.transform(
              nodes,
              new Function<org.eclipse.jdt.core.dom.Expression, Expression>() {
                @Override
                public Expression apply(org.eclipse.jdt.core.dom.Expression expression) {
                  return convert(expression);
                }
              }));
    }

    private Collection<Statement> convert(org.eclipse.jdt.core.dom.Statement node) {
      switch (node.getNodeType()) {
        case ASTNode.ASSERT_STATEMENT:
          return singletonStatement(convert((org.eclipse.jdt.core.dom.AssertStatement) node));
        case ASTNode.EXPRESSION_STATEMENT:
          return singletonStatement(convert((org.eclipse.jdt.core.dom.ExpressionStatement) node));
        case ASTNode.VARIABLE_DECLARATION_STATEMENT:
          return convert((org.eclipse.jdt.core.dom.VariableDeclarationStatement) node);
        default:
          throw new RuntimeException(
              "Need to implement translation for statement type: " + node.getClass().getName());
      }
    }

    private InstanceOfExpression convert(org.eclipse.jdt.core.dom.InstanceofExpression node) {
      Expression leftOperand = convert(node.getLeftOperand());
      TypeReference rightOperand = createTypeReference(node.getRightOperand().resolveBinding());
      return new InstanceOfExpression(leftOperand, rightOperand);
    }

    private Collection<Statement> singletonStatement(Statement statement) {
      return Collections.singletonList(statement);
    }

    private AssertStatement convert(org.eclipse.jdt.core.dom.AssertStatement node) {
      Expression message = node.getMessage() == null ? null : convert(node.getMessage());
      Expression expression = convert(node.getExpression());
      return new AssertStatement(expression, message);
    }

    private Block convert(org.eclipse.jdt.core.dom.Block node) {
      List<Statement> body = new ArrayList<>();
      for (Object object : node.statements()) {
        org.eclipse.jdt.core.dom.Statement statement = (org.eclipse.jdt.core.dom.Statement) object;
        body.addAll(convert(statement));
      }
      return new Block(body);
    }

    private Statement convert(org.eclipse.jdt.core.dom.ExpressionStatement node) {
      return new ExpressionStatement(convert(node.getExpression()));
    }

    private BinaryExpression convert(org.eclipse.jdt.core.dom.InfixExpression node) {
      Expression leftOperand = convert(node.getLeftOperand());
      Expression rightOperand = convert(node.getRightOperand());
      BinaryOperator operator = JdtUtils.getBinaryOperator(node.getOperator());
      BinaryExpression binaryExpression = new BinaryExpression(leftOperand, operator, rightOperand);
      for (Object object : node.extendedOperands()) {
        org.eclipse.jdt.core.dom.Expression extendedOperand =
            (org.eclipse.jdt.core.dom.Expression) object;
        binaryExpression =
            new BinaryExpression(binaryExpression, operator, convert(extendedOperand));
      }
      return binaryExpression;
    }

    private NumberLiteral convert(org.eclipse.jdt.core.dom.NumberLiteral node) {
      return new NumberLiteral(node.getToken());
    }

    private Expression convert(org.eclipse.jdt.core.dom.SimpleName node) {
      IBinding binding = node.resolveBinding();
      if (binding instanceof IVariableBinding) {
        IVariableBinding variableBinding = (IVariableBinding) binding;
        if (variableBinding.isField()) {
          // TODO: to be implemented.
          throw new RuntimeException(
              "Need to implement translation for SimpleName binding: " + node.getClass().getName());
        } else if (variableBinding.isParameter()) {
          // TODO: to be implemented.
          throw new RuntimeException(
              "Need to implement translation for SimpleName binding: " + node.getClass().getName());
        } else {
          // local variable.
          return new VariableReference(variableByJdtBinding.get(variableBinding));
        }
      } else {
        // TODO: to be implemented
        throw new RuntimeException(
            "Need to implement translation for SimpleName binding: " + node.getClass().getName());
      }
    }

    private ParenthesizedExpression convert(org.eclipse.jdt.core.dom.ParenthesizedExpression node) {
      return new ParenthesizedExpression(convert(node.getExpression()));
    }

    private PostfixExpression convert(org.eclipse.jdt.core.dom.PostfixExpression node) {
      return new PostfixExpression(
          convert(node.getOperand()), JdtUtils.getPostfixOperator(node.getOperator()));
    }

    private PrefixExpression convert(org.eclipse.jdt.core.dom.PrefixExpression node) {
      return new PrefixExpression(
          convert(node.getOperand()), JdtUtils.getPrefixOperator(node.getOperator()));
    }

    private VariableDeclaration convert(org.eclipse.jdt.core.dom.VariableDeclarationFragment node) {
      IVariableBinding variableBinding = node.resolveBinding();
      Variable variable = JdtUtils.createVariable(variableBinding, compilationUnitNameLocator);
      Expression initializer =
          node.getInitializer() == null ? null : convert(node.getInitializer());
      variableByJdtBinding.put(variableBinding, variable);
      return new VariableDeclaration(variable, initializer);
    }

    private Collection<Statement> convert(
        org.eclipse.jdt.core.dom.VariableDeclarationStatement node) {
      List<Statement> variableDeclarations = new ArrayList<>();
      for (Object object : node.fragments()) {
        org.eclipse.jdt.core.dom.VariableDeclarationFragment fragment =
            (org.eclipse.jdt.core.dom.VariableDeclarationFragment) object;
        variableDeclarations.add(convert(fragment));
      }
      return variableDeclarations;
    }

    private JavaType createJavaType(ITypeBinding typeBinding) {
      if (typeBinding == null) {
        return null;
      }
      JavaType type =
          new JavaType(
              typeBinding.isInterface() ? Kind.INTERFACE : Kind.CLASS,
              JdtUtils.getVisibility(typeBinding.getModifiers()),
              createTypeReference(typeBinding));

      ITypeBinding superclassBinding = typeBinding.getSuperclass();
      TypeReference superType = createTypeReference(superclassBinding);
      type.setSuperType(superType);
      return type;
    }
  }

  private CompilationUnit j2clCompilationUnit;
  private CompilationUnitNameLocator compilationUnitNameLocator;

  private CompilationUnit buildCompilationUnit(
      String sourceFilePath, org.eclipse.jdt.core.dom.CompilationUnit jdtCompilationUnit) {
    ASTConverter converter = new ASTConverter();
    j2clCompilationUnit = converter.convert(sourceFilePath, jdtCompilationUnit);
    return j2clCompilationUnit;
  }

  private TypeReference createTypeReference(ITypeBinding typeBinding) {
    if (typeBinding == null) {
      return null;
    }
    TypeReference typeReference =
        JdtUtils.createTypeReference(typeBinding, compilationUnitNameLocator);
    return typeReference;
  }

  public static List<CompilationUnit> build(
      Map<String, org.eclipse.jdt.core.dom.CompilationUnit> jdtUnitsByFilePath,
      FrontendOptions options,
      Errors errors) {
    CompilationUnitBuilder compilationUnitBuilder =
        new CompilationUnitBuilder(
            new CompilationUnitNameLocator(jdtUnitsByFilePath, options, errors));

    List<CompilationUnit> compilationUnits = new ArrayList<>();
    for (Entry<String, org.eclipse.jdt.core.dom.CompilationUnit> entry :
        jdtUnitsByFilePath.entrySet()) {
      compilationUnits.add(
          compilationUnitBuilder.buildCompilationUnit(entry.getKey(), entry.getValue()));
    }
    return compilationUnits;
  }

  private CompilationUnitBuilder(CompilationUnitNameLocator compilationUnitNameLocator) {
    this.compilationUnitNameLocator = compilationUnitNameLocator;
  }
}
