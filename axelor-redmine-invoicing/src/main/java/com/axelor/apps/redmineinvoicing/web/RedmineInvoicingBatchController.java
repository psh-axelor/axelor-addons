package com.axelor.apps.redmineinvoicing.web;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmineinvoicing.db.RedmineInvoicingBatch;
import com.axelor.apps.redmineinvoicing.db.repo.RedmineInvoicingBatchRepository;
import com.axelor.apps.redmineinvoicing.service.batch.RedmineInvoicingBatchService;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;

public class RedmineInvoicingBatchController {

  @Inject private RedmineInvoicingBatchRepository redmineInvoicingBatchRepo;

  @Inject private RedmineInvoicingBatchService redmineInvoicingBatchService;

  public void actionImportAll(ActionRequest request, ActionResponse response) {

    RedmineInvoicingBatch redmineInvoicingBatch =
        request.getContext().asType(RedmineInvoicingBatch.class);
    Batch batch =
        redmineInvoicingBatchService.importAll(
            redmineInvoicingBatchRepo.find(redmineInvoicingBatch.getId()));

    if (batch != null) {
      response.setFlash(IMessage.BATCH_IMPORT_1);
    }

    response.setReload(true);
  }
}
