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
import com.axelor.apps.redmine.service.RedmineService;
import com.axelor.auth.db.User;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.TimeEntryManager;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.TimeEntry;
import com.taskadapter.redmineapi.bean.TimeEntryActivity;
import com.taskadapter.redmineapi.internal.Transport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

  public String failedTimesheetLinesIds = "";
  public static int success = 0, fail = 0;
  public static String result = "";

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

      List<TimesheetLine> timesheetLineList =
          lastBatchEndDate != null
              ? (StringUtils.isNotEmpty(batch.getRedmineBatch().getFailedRedmineTimeEntriesIds())
                  ? timesheetLineRepo
                      .all()
                      .filter(
                          "self.updatedOn > ?1 or self.id in (?2)",
                          lastBatchEndDate,
                          batch.getRedmineBatch().getFailedRedmineTimeEntriesIds())
                      .fetch()
                  : timesheetLineRepo.all().filter("self.updatedOn > ?1", lastBatchEndDate).fetch())
              : timesheetLineRepo.all().fetch();

      if (CollectionUtils.isNotEmpty(timesheetLineList)) {
        Transport redmineTransport = redmineManager.getTransport();
        TimeEntryManager redmineTimeEntryManager = redmineManager.getTimeEntryManager();
        UserManager redmineUserManager = redmineManager.getUserManager();

        HashMap<String, Integer> redmineTimeEntryActivityMap = new HashMap<>();

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

        for (TimesheetLine timesheetLine : timesheetLineList) {

          try {
            exportTimesheetLine(
                timesheetLine,
                redmineTimeEntryManager,
                redmineUserManager,
                redmineTransport,
                lastBatchUpdatedOn,
                redmineTimeEntryActivityMap);
          } catch (Exception e) {
            TraceBackService.trace(e, "", batch.getId());
            incrementAnomaly();
          }
        }
      }
    }

    batch.getRedmineBatch().setFailedRedmineTimeEntriesIds(failedTimesheetLinesIds);

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

  protected void exportTimesheetLine(
      TimesheetLine timesheetLine,
      TimeEntryManager redmineTimeEntryManager,
      UserManager redmineUserManager,
      Transport redmineTransport,
      LocalDateTime lastBatchUpdatedOn,
      HashMap<String, Integer> redmineTimeEntryActivityMap)
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
      } else if (lastBatchUpdatedOn != null) {
        LocalDateTime timesheetLineUpdatedOn = timesheetLine.getUpdatedOn();
        LocalDateTime redmineTimeEntryUpdatedOn =
            redmineTimeEntry
                .getUpdatedOn()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        if (timesheetLineUpdatedOn.isBefore(lastBatchUpdatedOn)
            || (redmineTimeEntryUpdatedOn.isAfter(lastBatchUpdatedOn)
                && redmineTimeEntryUpdatedOn.isAfter(timesheetLineUpdatedOn))) {
          return;
        }
      }

      createRedmineTimeEntry(
          redmineTimeEntry,
          timesheetLine,
          project,
          redmineUserManager,
          redmineTimeEntryActivityMap);
    } else {
      failedTimesheetLinesIds =
          StringUtils.isEmpty(failedTimesheetLinesIds)
              ? String.valueOf(timesheetLine.getId())
              : failedTimesheetLinesIds + "," + timesheetLine.getId();
      fail++;
    }
  }

  @Transactional
  protected void createRedmineTimeEntry(
      TimeEntry redmineTimeEntry,
      TimesheetLine timesheetLine,
      Project project,
      UserManager redmineUserManager,
      HashMap<String, Integer> redmineTimeEntryActivityMap)
      throws AxelorException, RedmineException {

    if (project.getRedmineId() != null && project.getRedmineId() != 0) {
      redmineTimeEntry.setProjectId(project.getRedmineId());
    } else {
      failedTimesheetLinesIds =
          StringUtils.isEmpty(failedTimesheetLinesIds)
              ? String.valueOf(timesheetLine.getId())
              : failedTimesheetLinesIds + "," + timesheetLine.getId();
      fail++;
      return;
    }

    if (timesheetLine.getTeamTask() != null
        && timesheetLine.getTeamTask().getRedmineId() != null
        && timesheetLine.getTeamTask().getRedmineId() != 0) {
      redmineTimeEntry.setIssueId(timesheetLine.getTeamTask().getRedmineId());
    } else {
      failedTimesheetLinesIds =
          StringUtils.isEmpty(failedTimesheetLinesIds)
              ? String.valueOf(timesheetLine.getId())
              : failedTimesheetLinesIds + "," + timesheetLine.getId();
      fail++;
      return;
    }

    if (redmineTimeEntryActivityMap.containsKey(timesheetLine.getActivityTypeSelect())) {
      redmineTimeEntry.setActivityId(
          redmineTimeEntryActivityMap.get(timesheetLine.getActivityTypeSelect()));
    } else {
      failedTimesheetLinesIds =
          StringUtils.isEmpty(failedTimesheetLinesIds)
              ? String.valueOf(timesheetLine.getId())
              : failedTimesheetLinesIds + "," + timesheetLine.getId();
      fail++;
      return;
    }

    redmineTimeEntry.setComment(timesheetLine.getComments());
    redmineTimeEntry.setSpentOn(
        Date.from(timesheetLine.getDate().atStartOfDay(ZoneId.of("UTC")).toInstant()));
    redmineTimeEntry.setHours(
        timesheetLine.getDuration() != null
            ? timesheetLine.getDuration().floatValue()
            : BigDecimal.ZERO.floatValue());

    EmailAddress emailAddress = getEmailAddress(timesheetLine.getUser());
    HashMap<String, String> filterParam = new HashMap<>();
    filterParam.put("name", emailAddress.getName());
    List<com.taskadapter.redmineapi.bean.User> redmineUsers =
        redmineUserManager.getUsers(filterParam).getResults();

    if (CollectionUtils.isNotEmpty(redmineUsers)) {
      redmineTimeEntry.setUserId(redmineUsers.get(0).getId());
    }

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
}
