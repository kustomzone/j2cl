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
 * Synchronized statement.
 */
@Visitable
public class SynchronizedStatement extends Statement {
  @Visitable Expression expression;
  @Visitable Block body;

  public SynchronizedStatement(Expression expression, Block body) {
    this.expression = checkNotNull(expression);
    this.body = checkNotNull(body);
  }

  public Expression getExpression() {
    return expression;
  }

  public Block getBody() {
    return body;
  }

  @Override
  public SynchronizedStatement clone() {
    SynchronizedStatement synchronizedStatement =
        new SynchronizedStatement(expression.clone(), body.clone());
    synchronizedStatement.setSourcePosition(this.getSourcePosition());
    return synchronizedStatement;
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_SynchronizedStatement.visit(processor, this);
  }
}
