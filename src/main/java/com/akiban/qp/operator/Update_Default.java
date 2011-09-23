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

package com.akiban.qp.operator;

import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.exec.UpdateResult;
import com.akiban.qp.row.Row;
import com.akiban.util.ArgumentValidation;
import com.akiban.util.Strings;
import com.akiban.util.Tap;

import java.util.Collections;
import java.util.List;

class Update_Default implements UpdatePlannable {

    // Object interface

    @Override
    public String toString() {
        return String.format("%s(%s -> %s)", getClass().getSimpleName(), inputOperator, updateFunction);
    }

    // constructor

    public Update_Default(Operator inputOperator, UpdateFunction updateFunction) {
        ArgumentValidation.notNull("update lambda", updateFunction);
        
        this.inputOperator = inputOperator;
        this.updateFunction = updateFunction;
    }

    // UpdatePlannable interface

    @Override
    public UpdateResult run(Bindings bindings, StoreAdapter adapter) {
        int seen = 0, modified = 0;
        UPDATE_TAP.in();
        Cursor inputCursor = inputOperator.cursor(adapter);
        inputCursor.open(bindings);
        try {
            Row oldRow;
            while ((oldRow = inputCursor.next()) != null) {
                ++seen;
                if (updateFunction.rowIsSelected(oldRow)) {
                    Row newRow = updateFunction.evaluate(oldRow, bindings);
                    adapter.updateRow(oldRow, newRow, bindings);
                    ++modified;
                }
            }
        } finally {
            inputCursor.close();
            UPDATE_TAP.out();
        }
        return new StandardUpdateResult(seen, modified);
    }

    // Plannable interface

    @Override
    public List<Operator> getInputOperators() {
        return Collections.singletonList(inputOperator);
    }

    @Override
    public String describePlan()
    {
        return describePlan(inputOperator);
    }

    @Override
    public String describePlan(Operator inputOperator) {
        return inputOperator + Strings.nl() + this;
    }

    // Object state

    private final Operator inputOperator;
    private final UpdateFunction updateFunction;
    private static final Tap.InOutTap UPDATE_TAP = Tap.createTimer("operator: update");
    
}