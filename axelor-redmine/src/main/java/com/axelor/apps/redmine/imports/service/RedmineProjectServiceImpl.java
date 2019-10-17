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
import com.axelor.apps.redmine.imports.service.log.RedmineErrorLogService;
import com.axelor.apps.redmine.imports.service.projects.RedmineImportProjectService;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.RedmineManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

public class RedmineProjectServiceImpl implements RedmineProjectService {

  protected RedmineImportProjectService redmineImportProjectService;
  protected RedmineProjectFetchDataService redmineProjectFetchImportDataService;
  protected RedmineErrorLogService redmineErrorLogService;
  protected BatchRepository batchRepo;
  protected RedmineImportMappingRepository redmineImportMappingRepository;

  @Inject
  public RedmineProjectServiceImpl(
      RedmineImportProjectService redmineImportProjectService,
      RedmineProjectFetchDataService redmineProjectFetchImportDataService,
      RedmineErrorLogService redmineErrorLogService,
      BatchRepository batchRepo,
      RedmineImportMappingRepository redmineImportMappingRepository) {

    this.redmineImportProjectService = redmineImportProjectService;
    this.redmineProjectFetchImportDataService = redmineProjectFetchImportDataService;
    this.redmineErrorLogService = redmineErrorLogService;
    this.batchRepo = batchRepo;
    this.redmineImportMappingRepository = redmineImportMappingRepository;
  }

  @Override
  public void redmineImportProject(
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

    LocalDateTime lastBatchUpdatedOn = lastBatch != null ? lastBatch.getUpdatedOn() : null;

    List<com.taskadapter.redmineapi.bean.Project> importProjectList =
        redmineProjectFetchImportDataService.fetchImportData(redmineManager);

    // CREATE MAP FOR PASS TO THE METHODS

    HashMap<String, Object> paramsMap = new HashMap<String, Object>();

    paramsMap.put("onError", onError);
    paramsMap.put("onSuccess", onSuccess);
    paramsMap.put("batch", batch);
    paramsMap.put("redmineIssueManager", redmineManager.getIssueManager());
    paramsMap.put("redmineUserManager", redmineManager.getUserManager());
    paramsMap.put("redmineProjectManager", redmineManager.getProjectManager());
    paramsMap.put("redmineTransport", redmineManager.getTransport());
    paramsMap.put("errorObjList", errorObjList);
    paramsMap.put("lastBatchUpdatedOn", lastBatchUpdatedOn);

    // MAPPING CONFIG FOR SELECTIONS

    HashMap<String, String> importFieldMap = new HashMap<String, String>();

    List<RedmineImportMapping> redmineImportMappingList =
        redmineImportMappingRepository.all().fetch();

    for (RedmineImportMapping redmineImportMapping : redmineImportMappingList) {
      importFieldMap.put(redmineImportMapping.getRedmineValue(), redmineImportMapping.getOsValue());
    }

    // IMPORT PROCESS

    redmineImportProjectService.importProject(importProjectList, paramsMap, importFieldMap);

    // ATTACH ERROR LOG WITH BATCH

    if (errorObjList != null && errorObjList.size() > 0) {
      MetaFile errorMetaFile = redmineErrorLogService.redmineErrorLogService(errorObjList);

      if (errorMetaFile != null) {
        batch.setErrorLogFile(errorMetaFile);
      }
    }
  }
}
