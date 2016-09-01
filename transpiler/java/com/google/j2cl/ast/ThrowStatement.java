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
package com.google.j2cl.ast;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.j2cl.ast.annotations.Visitable;

/**
 * Class for throw statement.
 */
@Visitable
public class ThrowStatement extends Statement {
  @Visitable Expression expression;

  public ThrowStatement(Expression expression) {
    this.expression = checkNotNull(expression);
  }

  public Expression getExpression() {
    return expression;
  }

  public void setExpression(Expression expression) {
    this.expression = expression;
  }

  @Override
  public ThrowStatement clone() {
    ThrowStatement throwStatement = new ThrowStatement(expression.clone());
    throwStatement.setSourcePosition(this.getSourcePosition());
    return throwStatement;
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_ThrowStatement.visit(processor, this);
  }
}
