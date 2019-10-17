/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
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
package com.axelor.apps.redmine.imports.service;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.redmine.db.RedmineImportMapping;
import com.axelor.apps.redmine.db.repo.RedmineImportMappingRepository;
import com.axelor.apps.redmine.imports.service.issues.RedmineImportIssueService;
import com.axelor.apps.redmine.imports.service.issues.RedmineImportTimeSpentService;
import com.axelor.apps.redmine.imports.service.log.RedmineErrorLogService;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaStore;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.views.Selection.Option;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.TimeEntry;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class RedmineIssueServiceImpl implements RedmineIssueService {

  protected RedmineImportIssueService redmineImportIssueService;
  protected RedmineImportTimeSpentService redmineImportTimeSpentService;
  protected RedmineIssueFetchDataService redmineIssueFetchImportDataService;
  protected RedmineErrorLogService redmineErrorLogService;
  protected BatchRepository batchRepo;
  protected RedmineImportMappingRepository redmineImportMappingRepository;

  @Inject
  public RedmineIssueServiceImpl(
      RedmineImportIssueService redmineImportIssueService,
      RedmineImportTimeSpentService redmineImportTimeSpentService,
      RedmineIssueFetchDataService redmineIssueFetchImportDataService,
      RedmineErrorLogService redmineErrorLogService,
      BatchRepository batchRepo,
      RedmineImportMappingRepository redmineImportMappingRepository) {

    this.redmineImportIssueService = redmineImportIssueService;
    this.redmineImportTimeSpentService = redmineImportTimeSpentService;
    this.redmineIssueFetchImportDataService = redmineIssueFetchImportDataService;
    this.redmineErrorLogService = redmineErrorLogService;
    this.batchRepo = batchRepo;
    this.redmineImportMappingRepository = redmineImportMappingRepository;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void redmineImportIssue(
      Batch batch,
      RedmineManager redmineManager,
      Consumer<Object> onSuccess,
      Consumer<Throwable> onError) {

    RedmineImportService.result = "";

    // LOG REDMINE IMPORT ERROR DATA

    List<Object[]> errorObjList = new ArrayList<Object[]>();

    // FETCH IMPORT DATA

    Batch lastBatch =
        batchRepo
            .all()
            .filter(
                "self.id != ?1 and self.redmineBatch.id = ?2",
                batch.getId(),
                batch.getRedmineBatch().getId())
            .order("-updatedOn")
            .fetchOne();

    ZonedDateTime lastBatchEndDate = lastBatch != null ? lastBatch.getEndDate() : null;
    LocalDateTime lastBatchUpdatedOn = lastBatch != null ? lastBatch.getUpdatedOn() : null;

    Map<String, List<?>> importDataMap = new HashMap<>();
    try {
      importDataMap =
          redmineIssueFetchImportDataService.fetchImportData(redmineManager, lastBatchEndDate);
    } catch (RedmineException e) {
      e.printStackTrace();
    }

    // CREATE MAP FOR PASS TO THE METHODS

    HashMap<String, Object> paramsMap = new HashMap<String, Object>();

    paramsMap.put("onError", onError);
    paramsMap.put("onSuccess", onSuccess);
    paramsMap.put("batch", batch);
    paramsMap.put("redmineIssueManager", redmineManager.getIssueManager());
    paramsMap.put("redmineUserManager", redmineManager.getUserManager());
    paramsMap.put("redmineProjectManager", redmineManager.getProjectManager());
    paramsMap.put("redmineTimeEntryManager", redmineManager.getTimeEntryManager());
    paramsMap.put("redmineTransport", redmineManager.getTransport());
    paramsMap.put("errorObjList", errorObjList);
    paramsMap.put("lastBatchUpdatedOn", lastBatchUpdatedOn);

    // MAPPING CONFIG FOR SELECTIONS

    HashMap<String, String> importSelectionMap = new HashMap<String, String>();
    HashMap<String, String> importFieldMap = new HashMap<String, String>();

    List<Option> selectionList = new ArrayList<Option>();
    selectionList.addAll(MetaStore.getSelectionList("team.task.status"));
    selectionList.addAll(MetaStore.getSelectionList("team.task.priority"));

    ResourceBundle fr = I18n.getBundle(Locale.FRANCE);
    ResourceBundle en = I18n.getBundle(Locale.ENGLISH);

    for (Option option : selectionList) {
      importSelectionMap.put(fr.getString(option.getTitle()), option.getValue());
      importSelectionMap.put(en.getString(option.getTitle()), option.getValue());
    }

    List<RedmineImportMapping> redmineImportMappingList =
        redmineImportMappingRepository.all().fetch();

    for (RedmineImportMapping redmineImportMapping : redmineImportMappingList) {
      importFieldMap.put(redmineImportMapping.getRedmineValue(), redmineImportMapping.getOsValue());
    }

    // IMPORT PROCESS

    redmineImportIssueService.importIssue(
        (List<Issue>) importDataMap.get("importIssueList"),
        paramsMap,
        importSelectionMap,
        importFieldMap);
    redmineImportTimeSpentService.importTimeSpent(
        (List<TimeEntry>) importDataMap.get("importTimeEntryList"), paramsMap);

    // ATTACH ERROR LOG WITH BATCH

    if (errorObjList != null && errorObjList.size() > 0) {
      MetaFile errorMetaFile = redmineErrorLogService.redmineErrorLogService(errorObjList);

      if (errorMetaFile != null) {
        batch.setErrorLogFile(errorMetaFile);
      }
    }
  }
}
