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
package com.axelor.apps.redmine.service.batch;

import com.axelor.apps.base.db.AppRedmine;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.AppRedmineRepository;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.apps.message.db.EmailAddress;
import com.axelor.apps.message.db.repo.EmailAddressRepository;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.redmine.imports.service.log.RedmineErrorLogService;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmine.service.RedmineService;
import com.axelor.apps.tool.StringTool;
import com.axelor.auth.db.User;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.meta.db.MetaFile;
import com.axelor.team.db.TeamTask;
import com.google.common.base.Strings;
import com.google.common.collect.ObjectArrays;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.TimeEntryManager;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.TimeEntry;
import com.taskadapter.redmineapi.bean.TimeEntryActivity;
import com.taskadapter.redmineapi.internal.Transport;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;

public class BatchRedmineExportTimesheetline extends AbstractBatch {

  @Inject protected TimesheetLineRepository timesheetLineRepo;
  @Inject protected AppRedmineRepository appRedmineRepo;
  @Inject protected RedmineService redmineService;
  @Inject protected EmailAddressRepository emailAddressRepository;
  @Inject protected RedmineErrorLogService redmineErrorLogService;

  protected String failedTimesheetLinesIds = "";
  protected static int success = 0, fail = 0;
  protected static String result = "";

  protected LocalDateTime lastBatchEndDate;
  protected Transport redmineTransport;
  protected TimeEntryManager redmineTimeEntryManager;
  protected UserManager redmineUserManager;

  protected HashMap<String, Integer> redmineTimeEntryActivityMap = new HashMap<>();
  protected HashMap<String, Integer> redmineUserEmailMap = new HashMap<>();
  protected HashMap<String, String> redmineUserLoginMap = new HashMap<>();
  protected HashMap<Long, String> aosUserEmailMap = new HashMap<>();

  protected List<Object[]> errorObjList = new ArrayList<>();
  protected Object[] errors;

  @Override
  protected void process() {

    AppRedmine appRedmine = appRedmineRepo.all().fetchOne();
    RedmineManager redmineManager = null;

    try {
      redmineManager = redmineService.getRedmineManager(appRedmine);
    } catch (AxelorException e) {
      redmineManager = null;
      TraceBackService.trace(e, "", batch.getId());
      incrementAnomaly();
    }

    if (redmineManager != null) {
      List<TimesheetLine> timesheetLineList = fetchTimesheetLineList();

      if (CollectionUtils.isNotEmpty(timesheetLineList)) {
        setRedmineManagersAndMaps(redmineManager);

        for (TimesheetLine timesheetLine : timesheetLineList) {
          errors = new Object[] {};

          try {
            exportTimesheetLine(timesheetLine);
          } catch (Exception e) {
            TraceBackService.trace(e, "", batch.getId());
            incrementAnomaly();
          }
        }

        if (CollectionUtils.isNotEmpty(errorObjList)) {
          setErrorLog();
        }
      }

      batch.getRedmineBatch().setFailedRedmineTimeEntriesIds(failedTimesheetLinesIds);
    }

    String resultStr =
        String.format(
            "AOS Timesheetlines -> Redmine Timeentries : Success: %d Fail: %d", success, fail);
    result = String.format("%s \n", resultStr);
    success = fail = 0;
  }

  @Override
  protected void stop() {
    super.stop();
    addComment(result);
  }

  protected List<TimesheetLine> fetchTimesheetLineList() {

    Batch lastBatch =
        batchRepo
            .all()
            .filter(
                "self.id != ?1 and self.redmineBatch.id = ?2",
                batch.getId(),
                batch.getRedmineBatch().getId())
            .order("-updatedOn")
            .fetchOne();

    lastBatchEndDate =
        lastBatch != null && lastBatch.getEndDate() != null
            ? lastBatch.getEndDate().toLocalDateTime()
            : null;

    List<TimesheetLine> timesheetLineList =
        lastBatchEndDate != null
            ? (StringUtils.isNotEmpty(batch.getRedmineBatch().getFailedRedmineTimeEntriesIds())
                ? timesheetLineRepo
                    .all()
                    .filter(
                        "self.updatedOn > ?1 or self.id in (?2)",
                        lastBatchEndDate,
                        StringTool.getIntegerList(
                            batch.getRedmineBatch().getFailedRedmineTimeEntriesIds()))
                    .fetch()
                : timesheetLineRepo.all().filter("self.updatedOn > ?1", lastBatchEndDate).fetch())
            : timesheetLineRepo.all().fetch();

    return timesheetLineList;
  }

  protected void setRedmineManagersAndMaps(RedmineManager redmineManager) {

    redmineTransport = redmineManager.getTransport();
    redmineTimeEntryManager = redmineManager.getTimeEntryManager();
    redmineUserManager = redmineManager.getUserManager();

    try {
      List<TimeEntryActivity> redmineTimeEntryActivities =
          redmineTimeEntryManager.getTimeEntryActivities();

      if (CollectionUtils.isNotEmpty(redmineTimeEntryActivities)) {

        for (TimeEntryActivity redmineTimeEntryActivity : redmineTimeEntryActivities) {
          redmineTimeEntryActivityMap.put(
              redmineTimeEntryActivity.getName(), redmineTimeEntryActivity.getId());
        }
      }
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      incrementAnomaly();
    }
  }

  protected void exportTimesheetLine(TimesheetLine timesheetLine)
      throws RedmineException, AxelorException {

    LOG.debug("Exporting timesheetline: " + timesheetLine.getId());

    Project project = timesheetLine.getProject();

    if (project != null) {
      TimeEntry redmineTimeEntry = null;
      Integer redmineId = timesheetLine.getRedmineId();

      if (redmineId != null && redmineId != 0) {
        redmineTimeEntry = redmineTimeEntryManager.getTimeEntry(redmineId);
      }

      if (redmineTimeEntry == null) {
        redmineTimeEntry = new TimeEntry(redmineTransport);
      } else if (lastBatchEndDate != null) {
        LocalDateTime timesheetLineUpdatedOn = timesheetLine.getUpdatedOn();
        LocalDateTime redmineTimeEntryUpdatedOn =
            redmineTimeEntry
                .getUpdatedOn()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        if (timesheetLineUpdatedOn.isBefore(lastBatchEndDate)
            || (redmineTimeEntryUpdatedOn.isAfter(lastBatchEndDate)
                && redmineTimeEntryUpdatedOn.isAfter(timesheetLineUpdatedOn))) {
          return;
        }
      }

      updateRedmineTimeEntry(redmineTimeEntry, timesheetLine, project);
    } else {
      errors = new Object[] {I18n.get("Project is not set in AOS timesheetline")};
      updateFailedTimesheetLinesIds(timesheetLine.getId());
    }
  }

  @Transactional
  protected void updateRedmineTimeEntry(
      TimeEntry redmineTimeEntry, TimesheetLine timesheetLine, Project project)
      throws AxelorException, RedmineException {

    String address = null;

    if (aosUserEmailMap.containsKey(timesheetLine.getUser().getId())) {
      address = aosUserEmailMap.get(timesheetLine.getUser().getId());
    } else {
      aosUserEmailMap.put(timesheetLine.getUser().getId(), null);
      EmailAddress emailAddress = getEmailAddress(timesheetLine.getUser());
      address = emailAddress.getAddress();
      aosUserEmailMap.put(timesheetLine.getUser().getId(), address);
    }

    if (StringUtils.isEmpty(address)) {
      errors = new Object[] {I18n.get("Timesheetline user email address not set in AOS")};
      updateFailedTimesheetLinesIds(timesheetLine.getId());
      return;
    }

    if (redmineUserEmailMap.containsKey(address)) {

      if (redmineUserEmailMap.get(address) != null) {
        redmineTimeEntry.setUserId(redmineUserEmailMap.get(address));
        redmineTransport.setOnBehalfOfUser(redmineUserLoginMap.get(address));
      } else {
        errors =
            new Object[] {
              I18n.get(
                  "Redmine user not found with similar email address as AOS timesheetline user")
            };
        updateFailedTimesheetLinesIds(timesheetLine.getId());
        return;
      }
    } else {
      HashMap<String, String> filterParam = new HashMap<>();
      filterParam.put("name", address);
      List<com.taskadapter.redmineapi.bean.User> redmineUsers =
          redmineUserManager.getUsers(filterParam).getResults();

      if (CollectionUtils.isNotEmpty(redmineUsers)) {
        com.taskadapter.redmineapi.bean.User redmineUser = redmineUsers.get(0);
        redmineTimeEntry.setUserId(redmineUser.getId());
        redmineTransport.setOnBehalfOfUser(redmineUser.getLogin());
        redmineUserEmailMap.put(address, redmineUser.getId());
        redmineUserLoginMap.put(address, redmineUser.getLogin());
      } else {
        redmineUserEmailMap.put(address, null);
        errors =
            new Object[] {
              I18n.get(
                  "Redmine user not found with similar email address as AOS timesheetline user")
            };
        updateFailedTimesheetLinesIds(timesheetLine.getId());
        return;
      }
    }

    Integer projectRedmineId = project.getRedmineId();

    if (projectRedmineId != null && projectRedmineId != 0) {
      redmineTimeEntry.setProjectId(projectRedmineId);
    } else {
      errors = new Object[] {I18n.get("Redmine project not found for AOS timesheetline project")};
      updateFailedTimesheetLinesIds(timesheetLine.getId());
      return;
    }

    TeamTask teamTask = timesheetLine.getTeamTask();

    if (teamTask != null) {

      if (teamTask.getRedmineId() != null && teamTask.getRedmineId() != 0) {
        redmineTimeEntry.setIssueId(teamTask.getRedmineId());
      } else {
        errors = new Object[] {I18n.get("Redmine issue not found for AOS timesheetline task")};
        updateFailedTimesheetLinesIds(timesheetLine.getId());
        return;
      }
    }

    if (redmineTimeEntryActivityMap.containsKey(timesheetLine.getActivityTypeSelect())) {
      redmineTimeEntry.setActivityId(
          redmineTimeEntryActivityMap.get(timesheetLine.getActivityTypeSelect()));
    } else {
      errors =
          new Object[] {
            I18n.get("Redmine timeentry activity not found for AOS timesheetline activity")
          };
      updateFailedTimesheetLinesIds(timesheetLine.getId());
      return;
    }

    redmineTimeEntry.setComment(timesheetLine.getComments());
    redmineTimeEntry.setSpentOn(
        Date.from(timesheetLine.getDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
    redmineTimeEntry.setHours(timesheetLine.getDuration().floatValue());

    if (redmineTimeEntry.getId() == null) {
      redmineTimeEntry = redmineTimeEntry.create();
      timesheetLine.setRedmineId(redmineTimeEntry.getId());
      timesheetLineRepo.save(timesheetLine);
    } else {
      redmineTimeEntry.update();
    }

    success++;
    incrementDone();
  }

  protected EmailAddress getEmailAddress(User user) throws AxelorException {

    EmailAddress emailAddress = null;
    Employee employee = user.getEmployee();

    if (user.getEmployee() != null
        && employee.getContactPartner() != null
        && employee.getContactPartner().getEmailAddress() != null) {
      emailAddress = employee.getContactPartner().getEmailAddress();
    } else if (user.getPartner() != null && user.getPartner().getEmailAddress() != null) {
      emailAddress = user.getPartner().getEmailAddress();
    } else if (!Strings.isNullOrEmpty(user.getEmail())) {
      emailAddress = emailAddressRepository.findByAddress(user.getEmail());

      if (emailAddress == null) {
        emailAddress = new EmailAddress(user.getEmail());
      }
    }

    if (emailAddress == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get("User email is not configured"),
          user.getFullName());
    }

    return emailAddress;
  }

  protected void updateFailedTimesheetLinesIds(Long timesheetLineId) {

    failedTimesheetLinesIds =
        StringUtils.isEmpty(failedTimesheetLinesIds)
            ? String.valueOf(timesheetLineId)
            : failedTimesheetLinesIds + "," + timesheetLineId;

    errorObjList.add(
        ObjectArrays.concat(
            new Object[] {
              I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR), timesheetLineId.toString()
            },
            errors,
            Object.class));

    fail++;
  }

  protected void setErrorLog() {

    MetaFile errorMetaFile = redmineErrorLogService.redmineErrorLogService(errorObjList);

    if (errorMetaFile != null) {
      batch.setErrorLogFile(errorMetaFile);
    }
  }
}
