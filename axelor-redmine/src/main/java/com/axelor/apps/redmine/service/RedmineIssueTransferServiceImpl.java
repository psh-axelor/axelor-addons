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
package com.axelor.apps.redmine.service;

import com.axelor.apps.redmine.db.RedmineIssueTransfer;
import com.axelor.apps.redmine.db.RedmineIssueTransferConfig;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.taskadapter.redmineapi.AttachmentManager;
import com.taskadapter.redmineapi.Include;
import com.taskadapter.redmineapi.IssueManager;
import com.taskadapter.redmineapi.NotFoundException;
import com.taskadapter.redmineapi.Params;
import com.taskadapter.redmineapi.ProjectManager;
import com.taskadapter.redmineapi.RedmineAuthenticationException;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.RedmineManagerFactory;
import com.taskadapter.redmineapi.RedmineTransportException;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.Attachment;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.IssuePriority;
import com.taskadapter.redmineapi.bean.IssueStatus;
import com.taskadapter.redmineapi.bean.Project;
import com.taskadapter.redmineapi.bean.Tracker;
import com.taskadapter.redmineapi.bean.User;
import com.taskadapter.redmineapi.bean.Watcher;
import com.taskadapter.redmineapi.internal.Transport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineIssueTransferServiceImpl implements RedmineIssueTransferService {

  Logger LOG = LoggerFactory.getLogger(getClass());

  private ProjectManager targetProjectManager;
  private IssueManager sourceIssueManager;
  private IssueManager targetIssueManager;
  private AttachmentManager sourceAttachmentManager;
  private AttachmentManager targetAttachmentManager;
  private UserManager sourceUserManager;
  private UserManager targetUserManager;

  private Map<String, String> statusConfigMap;
  private Map<String, String> trackerConfigMap;
  private Map<String, String> priorityConfigMap;
  private Map<String, Integer> targetStatusMap;
  private Map<String, Tracker> targetTrackerMap;
  private Map<String, Integer> targetPriorityMap;
  private Map<Integer, String> sourceUserMap;
  private Map<String, Integer> targetUserMap;

  private RedmineIssueTransfer redmineIssueTransfer;
  private String targetProjectName;
  private Integer targetProjectId;

  private static Integer FETCH_LIMIT = 100;
  private static Integer TOTAL_FETCH_COUNT = 0;

  @Override
  public void redmineIssueTransfer(RedmineIssueTransfer redmineIssueTransfer)
      throws AxelorException, RedmineException, IOException {

    LOG.debug("Configuring for redmine transfer process..");

    // Configuring redmine managers for source and target

    RedmineManager sourceRedmineManager =
        this.getRedmineManager(
            redmineIssueTransfer.getSourceApiAccessKey(), redmineIssueTransfer.getSourceUri());
    RedmineManager targetRedmineManager =
        this.getRedmineManager(
            redmineIssueTransfer.getTargetApiAccessKey(), redmineIssueTransfer.getTargetUri());

    this.redmineIssueTransfer = redmineIssueTransfer;
    this.targetProjectManager = targetRedmineManager.getProjectManager();
    this.sourceIssueManager = sourceRedmineManager.getIssueManager();
    this.targetIssueManager = targetRedmineManager.getIssueManager();
    this.sourceAttachmentManager = sourceRedmineManager.getAttachmentManager();
    this.targetAttachmentManager = targetRedmineManager.getAttachmentManager();
    this.sourceUserManager = sourceRedmineManager.getUserManager();
    this.targetUserManager = targetRedmineManager.getUserManager();

    Transport targetTransport = targetRedmineManager.getTransport();

    // Configuring maps for transfer process

    this.configureMaps();

    HashMap<Integer, Integer> sourceTargetIssueMap = new HashMap<Integer, Integer>();
    HashMap<Integer, Integer> sourceTargetParentIssueMap = new HashMap<Integer, Integer>();

    List<RedmineIssueTransferConfig> projectTransferConfigList =
        redmineIssueTransfer.getProjectTransferConfigList();

    LOG.debug("Total projects for issues transfer: {}", projectTransferConfigList.size());

    for (RedmineIssueTransferConfig projectTransferConfig : projectTransferConfigList) {
      Project targetProject =
          targetProjectManager.getProjectByKey(projectTransferConfig.getTarget());
      this.targetProjectName = targetProject.getName();
      this.targetProjectId = targetProject.getId();

      ArrayList<Issue> targetParentIssueList = new ArrayList<Issue>();

      // Fetch issues for related project with attachments

      List<Issue> sourceIssuesList = this.fetchSourceIssues(projectTransferConfig.getSource());

      LOG.debug(
          "Total issues to transfer for source project {}: {}",
          projectTransferConfig.getSource(),
          sourceIssuesList.size());

      for (Issue sourceIssue : sourceIssuesList) {
        LOG.debug("Transferring source issue: {}", sourceIssue.getId());

        Issue targetIssue = new Issue();
        targetIssue = this.setTargetIssueFields(sourceIssue, targetIssue);
        targetIssue.setTransport(targetTransport);
        targetIssue = targetIssue.create();

        sourceTargetIssueMap.put(sourceIssue.getId(), targetIssue.getId());

        // To set parent if any

        if (sourceIssue.getParentId() != null) {
          sourceTargetParentIssueMap.put(targetIssue.getId(), sourceIssue.getParentId());
          targetParentIssueList.add(targetIssue);
        }

        // Add watchers to issue (need to update or remove as it is very time taking)

        Collection<Watcher> sourceIssueWatchers =
            sourceIssueManager.getIssueById(sourceIssue.getId(), Include.watchers).getWatchers();

        if (sourceIssueWatchers != null && !sourceIssueWatchers.isEmpty()) {

          for (Watcher watcher : sourceIssueWatchers) {
            Integer targetWatcherId = targetUserMap.get(sourceUserMap.get(watcher.getId()));

            if (targetWatcherId != null) {
              targetIssue.addWatcher(targetWatcherId);
            }
          }
        }

        // To update status if changed on targetIssue.create()

        targetIssue.setStatusName(statusConfigMap.get(sourceIssue.getStatusName()));
        targetIssue.setStatusId(targetStatusMap.get(targetIssue.getStatusName()));

        targetIssue.update();
      }

      // Run separate loop to set parent for issues having parent

      if (targetParentIssueList != null && !targetParentIssueList.isEmpty()) {

        for (Issue targetIssue : targetParentIssueList) {
          LOG.debug("Set parent for target issue: {}", targetIssue.getId());

          targetIssue.setParentId(
              sourceTargetIssueMap.get(sourceTargetParentIssueMap.get(targetIssue.getId())));
          targetIssue.update();
        }
      }

      LOG.debug("Transfer completed for project: {}", targetProjectName);
    }

    LOG.debug("Transfer completed successfully!!");
  }

  @Override
  public RedmineManager getRedmineManager(String api, String uri) throws AxelorException {

    RedmineManager redmineManager = RedmineManagerFactory.createWithApiKey(uri, api);

    try {
      redmineManager.getUserManager().getCurrentUser();
    } catch (RedmineTransportException | NotFoundException e) {
      throw new AxelorException(TraceBackRepository.CATEGORY_NO_VALUE, IMessage.REDMINE_TRANSPORT);
    } catch (RedmineAuthenticationException e) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_NO_VALUE, IMessage.REDMINE_AUTHENTICATION_2);
    } catch (RedmineException e) {
      throw new AxelorException(TraceBackRepository.CATEGORY_NO_VALUE, e.getLocalizedMessage());
    }

    return redmineManager;
  }

  private void configureMaps() throws RedmineException {

    this.statusConfigMap =
        redmineIssueTransfer
            .getStatusTransferConfigList()
            .stream()
            .collect(
                Collectors.toMap(
                    RedmineIssueTransferConfig::getSource, RedmineIssueTransferConfig::getTarget));

    this.trackerConfigMap =
        redmineIssueTransfer
            .getTrackerTransferConfigList()
            .stream()
            .collect(
                Collectors.toMap(
                    RedmineIssueTransferConfig::getSource, RedmineIssueTransferConfig::getTarget));

    this.priorityConfigMap =
        redmineIssueTransfer
            .getPriorityTransferConfigList()
            .stream()
            .collect(
                Collectors.toMap(
                    RedmineIssueTransferConfig::getSource, RedmineIssueTransferConfig::getTarget));

    this.targetStatusMap =
        targetIssueManager
            .getStatuses()
            .stream()
            .collect(Collectors.toMap(IssueStatus::getName, IssueStatus::getId));

    this.targetTrackerMap =
        targetIssueManager
            .getTrackers()
            .stream()
            .collect(Collectors.toMap(Tracker::getName, tracker -> tracker));

    this.targetPriorityMap =
        targetIssueManager
            .getIssuePriorities()
            .stream()
            .collect(Collectors.toMap(IssuePriority::getName, IssuePriority::getId));

    this.sourceUserMap =
        sourceUserManager.getUsers().stream().collect(Collectors.toMap(User::getId, User::getMail));

    this.targetUserMap =
        targetUserManager.getUsers().stream().collect(Collectors.toMap(User::getMail, User::getId));
  }

  private List<Issue> fetchSourceIssues(String sourceProjectKey) throws RedmineException {

    TOTAL_FETCH_COUNT = 0;
    List<com.taskadapter.redmineapi.bean.Issue> sourceIssueList =
        new ArrayList<com.taskadapter.redmineapi.bean.Issue>();

    Params params =
        new Params()
            .add("status_id", "*")
            .add("project_id", sourceProjectKey)
            .add("include", "watchers")
            .add("include", "attachments");

    List<Issue> tempIssueList;

    do {
      tempIssueList = fetchIssues(params);

      if (tempIssueList != null && tempIssueList.size() > 0) {
        sourceIssueList.addAll(tempIssueList);
        TOTAL_FETCH_COUNT += tempIssueList.size();
      } else {
        params = null;
      }
    } while (params != null);

    return sourceIssueList;
  }

  private List<Issue> fetchIssues(Params params) throws RedmineException {

    List<Issue> sourceIssueList = null;

    params.add("limit", FETCH_LIMIT.toString());
    params.add("offset", TOTAL_FETCH_COUNT.toString());
    sourceIssueList = sourceIssueManager.getIssues(params).getResults();

    return sourceIssueList;
  }

  private Issue setTargetIssueFields(Issue sourceIssue, Issue targetIssue)
      throws RedmineException, IOException {

    // Required fields

    targetIssue.setSubject(sourceIssue.getSubject());
    targetIssue.setProjectName(targetProjectName);
    targetIssue.setProjectId(targetProjectId);
    targetIssue.setTracker(
        targetTrackerMap.get(trackerConfigMap.get(sourceIssue.getTracker().getName())));
    targetIssue.setStatusName(statusConfigMap.get(sourceIssue.getStatusName()));
    targetIssue.setStatusId(targetStatusMap.get(targetIssue.getStatusName()));

    // Optional fields

    Integer priorityId =
        targetPriorityMap.get(priorityConfigMap.get(sourceIssue.getPriorityText()));

    if (priorityId != null) {
      targetIssue.setPriorityId(
          targetPriorityMap.get(priorityConfigMap.get(sourceIssue.getPriorityText())));
    }

    targetIssue.setDescription(sourceIssue.getDescription());
    targetIssue.setDoneRatio(sourceIssue.getDoneRatio());
    targetIssue.setDueDate(sourceIssue.getDueDate());
    targetIssue.setEstimatedHours(sourceIssue.getEstimatedHours());
    targetIssue.setSpentHours(sourceIssue.getSpentHours());
    targetIssue.setStartDate(sourceIssue.getStartDate());
    targetIssue.setCreatedOn(sourceIssue.getCreatedOn());
    targetIssue.setUpdatedOn(sourceIssue.getUpdatedOn());
    targetIssue.setClosedOn(sourceIssue.getClosedOn());

    if (sourceIssue.getAssigneeId() != null) {
      targetIssue.setAssigneeId(targetUserMap.get(sourceUserMap.get(sourceIssue.getAssigneeId())));
    }

    targetIssue.setAuthorId(targetUserMap.get(sourceUserMap.get(sourceIssue.getAuthorId())));

    // Transfer attachments

    Collection<Attachment> sourceAttachments = sourceIssue.getAttachments();

    if (sourceAttachments != null && !sourceAttachments.isEmpty()) {

      for (Attachment sourceAttachment : sourceAttachments) {
        byte[] content = sourceAttachmentManager.downloadAttachmentContent(sourceAttachment);
        Attachment attachment =
            targetAttachmentManager.uploadAttachment(
                sourceAttachment.getFileName(), sourceAttachment.getContentType(), content);
        targetIssue.addAttachment(attachment);
      }
    }

    return targetIssue;
  }
}
