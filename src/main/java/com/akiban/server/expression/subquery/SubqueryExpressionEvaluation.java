/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.expression.subquery;

import com.akiban.qp.operator.API;
import com.akiban.qp.operator.StoreAdapter;
import com.akiban.qp.operator.Bindings;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.operator.StoreAdapter;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.types.ValueSource;

public abstract class SubqueryExpressionEvaluation implements ExpressionEvaluation {

    @Override
    public void of(StoreAdapter adapter) {
        // TODO: This is a wrapping cursor. Is that all right? 
        // Need protected access for Operator.cursor().
        this.cursor = API.cursor(subquery, adapter);
    }

    @Override
    public void of(Bindings bindings) {
        this.bindings = bindings;
    }

    @Override
    public void of(Row row) {
        if (row.rowType() != outerRowType) {
            throw new IllegalArgumentException("wrong row type: " + outerRowType +
                                               " != " + row.rowType());
        }
        outerRow = row;
    }

    @Override
    public final ValueSource eval() {
        bindings.set(bindingPosition, outerRow);
        cursor.open(bindings);
        try {
            return doEval();
        }
        finally {
            cursor.close();
        }
    }

    // Shareable interface

    @Override
    public void acquire() {
        outerRow.acquire();
    }

    @Override
    public boolean isShared() {
        return outerRow.isShared();
    }

    @Override
    public void release() {
        outerRow.release();
    }

    // for use by subclasses

    protected abstract ValueSource doEval();

    protected Bindings bindings() {
        return bindings;
    }

    protected Row next() {
        Row row = cursor.next();
        if ((row != null) &&
            (row.rowType() != innerRowType)) {
            throw new IllegalArgumentException("wrong row type: " + innerRowType +
                                               " != " + row.rowType());
        }
        return row;
    }

    protected SubqueryExpressionEvaluation(Operator subquery,
                                           RowType outerRowType, RowType innerRowType, 
                                           int bindingPosition) {
        this.subquery = subquery;
        this.outerRowType = outerRowType;
        this.innerRowType = innerRowType;
        this.bindingPosition = bindingPosition;
    }

    private final Operator subquery;
    private final RowType outerRowType;
    private final RowType innerRowType;
    private final int bindingPosition;
    private Cursor cursor;
    private Bindings bindings;
    private Row outerRow;

}
