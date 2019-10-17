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

import com.taskadapter.redmineapi.Params;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.Issue;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedmineIssueFetchDataService {

  private ZonedDateTime lastBatchEndDate;
  private RedmineManager redmineManager;
  private HashMap<String, List<?>> importDataMap;

  private static Integer FETCH_LIMIT = 100;
  private static Integer TOTAL_FETCH_COUNT = 0;

  public Map<String, List<?>> fetchImportData(
      RedmineManager redmineManager, ZonedDateTime lastBatchEndDate) throws RedmineException {

    this.lastBatchEndDate = lastBatchEndDate;
    this.redmineManager = redmineManager;
    this.importDataMap = new HashMap<String, List<?>>();

    this.fetchImportIssueData();
    this.fetchImportTimeEntryData();

    return importDataMap;
  }

  public void fetchImportIssueData() throws RedmineException {

    TOTAL_FETCH_COUNT = 0;
    List<com.taskadapter.redmineapi.bean.Issue> importIssueList =
        new ArrayList<com.taskadapter.redmineapi.bean.Issue>();

    Params params = new Params();

    if (lastBatchEndDate != null) {
      ZonedDateTime endOn = lastBatchEndDate.withZoneSameInstant(ZoneOffset.UTC).withNano(0);

      params
          .add("set_filter", "1")
          .add("f[]", "updated_on")
          .add("op[updated_on]", ">=")
          .add("v[updated_on][]", endOn.toString());
    }

    List<Issue> tempIssueList;

    do {
      tempIssueList = fetchIssues(params);

      if (tempIssueList != null && tempIssueList.size() > 0) {
        importIssueList.addAll(tempIssueList);
        TOTAL_FETCH_COUNT += tempIssueList.size();
      }
    } while (tempIssueList != null && tempIssueList.size() > 0);

    importDataMap.put("importIssueList", importIssueList);
  }

  public List<Issue> fetchIssues(Params params) throws RedmineException {

    List<Issue> issueList = null;

    params.add("limit", FETCH_LIMIT.toString());
    params.add("offset", TOTAL_FETCH_COUNT.toString());
    issueList = redmineManager.getIssueManager().getIssues(params).getResults();

    return issueList;
  }

  public void fetchImportTimeEntryData() throws RedmineException {

    List<com.taskadapter.redmineapi.bean.TimeEntry> importTimeEntryList = null;

    Map<String, String> params = new HashMap<String, String>();

    if (lastBatchEndDate != null) {
      params.put("from", lastBatchEndDate.toLocalDate().toString());
    }

    importTimeEntryList = redmineManager.getTimeEntryManager().getTimeEntries(params).getResults();

    importDataMap.put("importTimeEntryList", importTimeEntryList);
  }
}
