/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmineinvoicing.service.batch;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.service.administration.AbstractBatchService;
import com.axelor.apps.redmine.db.RedmineBatch;
import com.axelor.apps.redmineinvoicing.db.RedmineInvoicingBatch;
import com.axelor.db.Model;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;

public class RedmineInvoicingBatchService extends AbstractBatchService {

  @Override
  protected Class<? extends Model> getModelClass() {
    return RedmineBatch.class;
  }

  @Override
  public Batch run(Model batchModel) throws AxelorException {
    Batch batch;
    RedmineInvoicingBatch redmineInvoicingBatch = (RedmineInvoicingBatch) batchModel;
    batch = importAll(redmineInvoicingBatch);

    return batch;
  }

  public Batch importAll(RedmineInvoicingBatch redmineInvoicingBatch) {
    return Beans.get(BatchImportAllRedmineInvoicing.class).run(redmineInvoicingBatch);
  }
}
