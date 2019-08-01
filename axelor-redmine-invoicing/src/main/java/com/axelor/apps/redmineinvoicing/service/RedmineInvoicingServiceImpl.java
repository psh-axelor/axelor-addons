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
package com.axelor.apps.redmineinvoicing.service;

import com.axelor.apps.base.db.AppRedmineInvoicing;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.AppRedmineInvoicingRepository;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.redmine.db.RedmineBatch;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmineinvoicing.imports.RedmineInvoicingImportService;
import com.axelor.common.StringUtils;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.NotFoundException;
import com.taskadapter.redmineapi.RedmineAuthenticationException;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.RedmineManagerFactory;
import com.taskadapter.redmineapi.RedmineTransportException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class RedmineInvoicingServiceImpl implements RedmineInvoicingService {

  @Inject private AppRedmineInvoicingRepository appRedmineInvoicingRepo;
  @Inject protected RedmineInvoicingImportService importService;

  @Override
  @Transactional
  public void importRedmine(Batch batch, Consumer<Object> onSuccess, Consumer<Throwable> onError)
      throws AxelorException, RedmineException {
    final RedmineManager redmineManager = getRedmineManager();
    if (redmineManager == null) {
      return;
    }

    LocalDateTime lastImportDateTime = getLastOperationDate(batch.getRedmineBatch());
    Date lastImportDate = null;
    if (lastImportDateTime != null) {
      lastImportDate = Date.from(lastImportDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
    importService.importRedmine(batch, lastImportDate, redmineManager, onSuccess, onError);
  }

  protected LocalDateTime getLastOperationDate(RedmineBatch redmineBatch) {
    Stream<Batch> stream =
        Beans.get(BatchRepository.class)
            .all()
            .filter(
                "self.redmineInvoicingBatch IS NOT NULL AND self.endDate IS NOT NULL AND self.done > 0")
            .fetchStream();
    if (stream != null) {
      Optional<Batch> lastRedmineBatch = stream.max(Comparator.comparing(Batch::getEndDate));
      if (lastRedmineBatch.isPresent()) {
        return lastRedmineBatch.get().getUpdatedOn();
      }
    }
    return null;
  }

  public RedmineManager getRedmineManager() throws AxelorException {
    AppRedmineInvoicing appRedmineInvoicing = appRedmineInvoicingRepo.all().fetchOne();

    if (!StringUtils.isBlank(appRedmineInvoicing.getUri())
        && !StringUtils.isBlank(appRedmineInvoicing.getApiAccessKey())) {
      RedmineManager redmineManager =
          RedmineManagerFactory.createWithApiKey(
              appRedmineInvoicing.getUri(), appRedmineInvoicing.getApiAccessKey());
      try {
        redmineManager.getUserManager().getCurrentUser();
      } catch (RedmineTransportException | NotFoundException e) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_NO_VALUE, IMessage.REDMINE_TRANSPORT);
      } catch (RedmineAuthenticationException e) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_NO_VALUE, IMessage.REDMINE_AUTHENTICATION_2);
      } catch (RedmineException e) {
        throw new AxelorException(TraceBackRepository.CATEGORY_NO_VALUE, e.getLocalizedMessage());
      }

      return redmineManager;
    } else {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_NO_VALUE, IMessage.REDMINE_AUTHENTICATION_1);
    }
  }
}
