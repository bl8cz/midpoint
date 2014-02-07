/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.web.component.data.column;

import com.evolveum.midpoint.web.component.button.ButtonType;
import org.apache.commons.lang.Validate;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.grid.ICellPopulator;
import org.apache.wicket.extensions.markup.html.repeater.data.table.AbstractColumn;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.PropertyModel;

/**
 * @author lazyman
 */
public class ButtonColumn<T> extends AbstractColumn<T, String> {

    private IModel<String> buttonLabel;
    private String propertyExpression;
    private ButtonType buttonType = ButtonType.SIMPLE;

    public ButtonColumn(IModel<String> displayModel, IModel<String> buttonLabel) {
        super(displayModel);
        this.buttonLabel = buttonLabel;
    }

    public ButtonColumn(IModel<String> displayModel, String propertyExpression) {
        super(displayModel);
        this.propertyExpression = propertyExpression;
    }

    @Override
    public void populateItem(Item<ICellPopulator<T>> cellItem, String componentId,
                             final IModel<T> rowModel) {
        IModel<String> label = buttonLabel;
        if (label == null) {
            label = new PropertyModel<String>(rowModel, propertyExpression);
        }

        cellItem.add(new ButtonPanel(componentId, label, buttonType) {

            @Override
            public void onClick(AjaxRequestTarget target) {
                ButtonColumn.this.onClick(target, rowModel);
            }
        });
    }

    public void onClick(AjaxRequestTarget target, IModel<T> rowModel) {

    }

    public void setButtonType(ButtonType buttonType) {
        Validate.notNull(buttonType, "Button type must not be null.");
        this.buttonType = buttonType;
    }
}