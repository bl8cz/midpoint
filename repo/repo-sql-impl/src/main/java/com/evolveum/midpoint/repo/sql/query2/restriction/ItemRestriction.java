/*
 * Copyright (c) 2010-2015 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.repo.sql.query2.restriction;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.repo.sql.query2.InterpretationContext;
import com.evolveum.midpoint.repo.sql.query2.ItemPathResolutionState;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaEntityDefinition;
import org.apache.commons.lang.Validate;

/**
 * Base for all item path-related restrictions, e.g. those that are based on item path that points to a JPA data node.
 *
 * @author mederly
 */
public abstract class ItemRestriction<T extends ObjectFilter> extends Restriction<T> {

    /**
     * Item path (relative to parent restriction), copied from the appropriate filter.
     * Not null, although possibly empty.
     */
    private ItemPath itemPath;

    /**
     * Item resolution state, i.e. "how we got to the given item".
     * Necessary to enable looking upwards via ".." operator.
     * Filled-in within interpret() method.
     */
    private ItemPathResolutionState itemResolutionState;

    public ItemRestriction(InterpretationContext context, T filter, ItemPath itemPath, JpaEntityDefinition baseEntityDefinition, Restriction parent) {
        super(context, filter, baseEntityDefinition, parent);
        if (itemPath != null) {
            this.itemPath = itemPath;
        } else {
            this.itemPath = ItemPath.EMPTY_PATH;
        }
    }

    public ItemPath getItemPath() {
        return itemPath;
    }

    public ItemPathResolutionState getItemResolutionState() {
        return itemResolutionState;
    }

    public void setItemResolutionState(ItemPathResolutionState itemResolutionState) {
        Validate.notNull(itemResolutionState);
        this.itemResolutionState = itemResolutionState;
    }
}