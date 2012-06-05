/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.qp.operator;

import com.akiban.qp.row.Row;
import com.akiban.qp.row.ValuesHolderRow;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.server.types.util.ValueHolder;
import com.akiban.util.ArgumentValidation;
import com.akiban.util.ShareHolder;
import com.akiban.util.tap.InOutTap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.lang.Math.abs;
import static java.lang.Math.min;

/**
 <h1>Overview</h1>

 Union_Ordered combines rows from two input streams. The input streams must be based on the same index, (a restriction
 that we expect to drop in the future). Duplicate rows are suppressed.

 <h1>Arguments</h1>

<li><b>Operator left:</b> Operator providing left input stream.
<li><b>Operator right:</b> Operator providing right input stream.
<li><b>IndexRowType leftRowType:</b> Type of rows from left input stream.
<li><b>IndexRowType rightRowType:</b> Type of rows from right input stream.
<li><b>int leftOrderingFields:</b> Number of trailing fields of left input rows to be used for ordering and matching rows.
<li><b>int rightOrderingFields:</b> Number of trailing fields of right input rows to be used for ordering and matching rows.
<li><b>boolean[] ascending:</b> The length of this arrays specifies the number of fields to be compared in the merge,
 (<= min(leftOrderingFields, rightOrderingFields). ascending[i] is true if the ith such field is ascending, false
 if it is descending.

 <h1>Behavior</h1>

 The two streams are merged, yielding an output stream ordered compatibly with the input streams.

 <h1>Output</h1>

 All input rows.

 <h1>Assumptions</h1>

 Each input stream is ordered by its ordering columns, as determined by <tt>leftOrderingFields</tt>
 and <tt>rightOrderingFields</tt>.

 For now: leftRowType == inputRowType and leftOrderingFields == rightOrderingFields.

 <h1>Performance</h1>

 This operator does no IO.

 <h1>Memory Requirements</h1>

 Two input rows, one from each stream.

 */

class Union_Ordered extends Operator
{
    // Object interface

    @Override
    public String toString()
    {
        return String.format("%s(skip %d, compare %d)",
                             getClass().getSimpleName(), fixedFields, ascending.length);
    }

    // Operator interface

    @Override
    protected Cursor cursor(QueryContext context)
    {
        return new Execution(context);
    }

    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes)
    {
        right.findDerivedTypes(derivedTypes);
        left.findDerivedTypes(derivedTypes);
    }

    @Override
    public List<Operator> getInputOperators()
    {
        List<Operator> result = new ArrayList<Operator>(2);
        result.add(left);
        result.add(right);
        return result;
    }

    @Override
    public String describePlan()
    {
        return String.format("%s\n%s", describePlan(left), describePlan(right));
    }

    // Project_Default interface

    public Union_Ordered(Operator left,
                         Operator right,
                         IndexRowType leftRowType,
                         IndexRowType rightRowType,
                         int leftOrderingFields,
                         int rightOrderingFields,
                         boolean[] ascending)
    {
        ArgumentValidation.notNull("left", left);
        ArgumentValidation.notNull("right", right);
        ArgumentValidation.notNull("leftRowType", leftRowType);
        ArgumentValidation.notNull("rightRowType", rightRowType);
        ArgumentValidation.isGTE("leftOrderingFields", leftOrderingFields, 0);
        ArgumentValidation.isLTE("leftOrderingFields", leftOrderingFields, leftRowType.nFields());
        ArgumentValidation.isGTE("rightOrderingFields", rightOrderingFields, 0);
        ArgumentValidation.isLTE("rightOrderingFields", rightOrderingFields, rightRowType.nFields());
        ArgumentValidation.isGTE("ascending.length()", ascending.length, 0);
        ArgumentValidation.isLTE("ascending.length()", ascending.length, min(leftOrderingFields, rightOrderingFields));
        // The following assumptions will be relaxed when this operator is generalized to support inputs from different
        // indexes.
        ArgumentValidation.isEQ("leftRowType", leftRowType, "rightRowType", rightRowType);
        ArgumentValidation.isEQ("leftOrderingFields", leftOrderingFields, "rightOrderingFields", rightOrderingFields);
        this.left = left;
        this.right = right;
        this.rowType = leftRowType;
        this.ascending = Arrays.copyOf(ascending, ascending.length);
        // Setup for row comparisons
        this.fixedFields = rowType.nFields() - leftOrderingFields;
    }

    // Class state

    private static final InOutTap TAP_OPEN = OPERATOR_TAP.createSubsidiaryTap("operator: Union_Ordered open");
    private static final InOutTap TAP_NEXT = OPERATOR_TAP.createSubsidiaryTap("operator: Union_Ordered next");
    private static final Logger LOG = LoggerFactory.getLogger(Union_Ordered.class);

    // Object state

    private final Operator left;
    private final Operator right;
    private IndexRowType rowType;
    private final int fixedFields;
    private final boolean[] ascending;

    // Inner classes

    private class Execution extends OperatorExecutionBase implements Cursor
    {
        // Cursor interface

        @Override
        public void open()
        {
            TAP_OPEN.in();
            try {
                CursorLifecycle.checkIdle(this);
                leftInput.open();
                rightInput.open();
                nextLeftRow();
                nextRightRow();
                closed = false;
                if (leftRow.isEmpty() && rightRow.isEmpty()) {
                    close();
                }
            } finally {
                TAP_OPEN.out();
            }
        }

        @Override
        public Row next()
        {
            TAP_NEXT.in();
            try {
                CursorLifecycle.checkIdleOrActive(this);
                Row next = null;
                if (isActive()) {
                    assert !(leftRow.isEmpty() && rightRow.isEmpty());
                    long c = compareRows();
                    if (c < 0) {
                        next = leftRow.get();
                        nextLeftRow();
                    } else if (c > 0) {
                        next = rightRow.get();
                        nextRightRow();
                    } else {
                        // left and right rows match. Doesn't matter which one is output.
                        next = leftRow.get();
                        nextLeftRow();
                        nextRightRow();
                    }
                    if (leftRow.isEmpty() && rightRow.isEmpty()) {
                        close();
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Union_Ordered: yield {}", next);
                    }
                }
                return next;
            } finally {
                TAP_NEXT.out();
            }
        }

        @Override
        public void jump(Row row, ColumnSelector columnSelector)
        {
            nextLeftRowSkip(row, fixedFields);
            nextRightRowSkip(row, fixedFields);
        }

        @Override
        public void close()
        {
            CursorLifecycle.checkIdleOrActive(this);
            if (!closed) {
                leftRow.release();
                rightRow.release();
                leftInput.close();
                rightInput.close();
                closed = true;
            }
        }

        @Override
        public void destroy()
        {
            close();
            leftInput.destroy();
            rightInput.destroy();
        }

        @Override
        public boolean isIdle()
        {
            return closed;
        }

        @Override
        public boolean isActive()
        {
            return !closed;
        }

        @Override
        public boolean isDestroyed()
        {
            assert leftInput.isDestroyed() == rightInput.isDestroyed();
            return leftInput.isDestroyed();
        }

        // Execution interface

        Execution(QueryContext context)
        {
            super(context);
            leftInput = left.cursor(context);
            rightInput = right.cursor(context);
            final int skipRowColumns = fixedFields + ascending.length;
            skipRowColumnSelector =
                new ColumnSelector()
                {
                    @Override
                    public boolean includesColumn(int columnPosition)
                    {
                        return columnPosition < skipRowColumns;
                    }
                };
        }
        
        // For use by this class
        
        private void nextLeftRow()
        {
            Row row = leftInput.next();
            leftRow.hold(row);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Union_Ordered: left {}", row);
            }
        }
        
        private void nextRightRow()
        {
            Row row = rightInput.next();
            rightRow.hold(row);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Union_Ordered: right {}", row);
            }
        }
        
        private long compareRows()
        {
            long c;
            assert !closed;
            assert !(leftRow.isEmpty() && rightRow.isEmpty());
            if (leftRow.isEmpty()) {
                c = 1;
            } else if (rightRow.isEmpty()) {
                c = -1;
            } else {
                c = leftRow.get().compareTo(rightRow.get(), fixedFields, fixedFields, ascending.length);
                if (c != 0) {
                    int fieldThatDiffers = (int) abs(c) - 1;
                    if (!ascending[fieldThatDiffers]) {
                        c = -c;
                    }
                }
            }
            return c;
        }

        private void nextLeftRowSkip(Row suffixRow, int suffixRowFixedFields)
        {
            addSuffixToSkipRow(leftSkipRow(),
                               fixedFields,
                               suffixRow,
                               suffixRowFixedFields,
                               ascending.length);
            leftInput.jump(leftSkipRow, skipRowColumnSelector);
            leftRow.hold(leftInput.next());
        }

        private void nextRightRowSkip(Row suffixRow, int suffixRowFixedFields)
        {
            addSuffixToSkipRow(rightSkipRow(),
                               fixedFields,
                               suffixRow,
                               suffixRowFixedFields,
                               ascending.length);
            rightInput.jump(rightSkipRow, skipRowColumnSelector);
            rightRow.hold(rightInput.next());
        }

        private void addSuffixToSkipRow(ValuesHolderRow skipRow,
                                        int skipRowFixedFields,
                                        Row suffixRow,
                                        int suffixRowFixedFields,
                                        int orderingFields)
        {
            if (suffixRow == null) {
                for (int f = 0; f < orderingFields; f++) {
                    skipRow.holderAt(skipRowFixedFields + f).putNull();
                }
            } else {
                for (int f = 0; f < orderingFields; f++) {
                    skipRow.holderAt(skipRowFixedFields + f).copyFrom(suffixRow.eval(suffixRowFixedFields + f));
                }
            }
        }

        private ValuesHolderRow leftSkipRow()
        {
            if (leftSkipRow == null) {
                assert leftRow.isHolding();
                leftSkipRow = new ValuesHolderRow(rowType);
                for (int f = 0; f < fixedFields; f++) {
                    leftSkipRow.holderAt(f).copyFrom(leftRow.get().eval(f));
                }
            }
            return leftSkipRow;
        }

        private ValuesHolderRow rightSkipRow()
        {
            if (rightSkipRow == null) {
                assert rightRow.isHolding();
                rightSkipRow = new ValuesHolderRow(rowType);
                for (int f = 0; f < fixedFields; f++) {
                    ValueHolder field = rightSkipRow.holderAt(f);
                    field.copyFrom(rightRow.get().eval(f));
                }
            }
            return rightSkipRow;
        }

        // Object state
        
        // Rows from each input stream are bound to the QueryContext. However, QueryContext doesn't use
        // ShareHolders, so they are needed here.

        private boolean closed = true;
        private final Cursor leftInput;
        private final Cursor rightInput;
        private final ShareHolder<Row> leftRow = new ShareHolder<Row>();
        private final ShareHolder<Row> rightRow = new ShareHolder<Row>();
        private ValuesHolderRow leftSkipRow;
        private ValuesHolderRow rightSkipRow;
        private ColumnSelector skipRowColumnSelector;
    }
}
