/**
Copyright 2014 Pieter Rautenbach

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package com.whatsthatlight.teamcity.hipchat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import jetbrains.buildServer.serverSide.Branch;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserSet;
import jetbrains.buildServer.vcs.SelectPrevBuildPolicy;

public class HipChatServerExtension extends BuildServerAdapter {

	private static Logger logger = Logger.getLogger("com.whatsthatlight.teamcity.hipchat");
	private SBuildServer server;
	private HipChatConfiguration configuration;
	private HipChatApiProcessor processor;
	private static Random rng = new Random();
	private String messageFormat;
	private HashMap<TeamCityEvent, HipChatMessageBundle> eventMap;
	private HashMap<String, String> emoticonCache;

	public HipChatServerExtension(@NotNull SBuildServer server, @NotNull HipChatConfiguration configuration, @NotNull HipChatApiProcessor processor) {
		this.server = server;
		this.configuration = configuration;
		this.processor = processor;
		this.messageFormat = HipChatMessageFormat.HTML;
		this.eventMap = new HashMap<TeamCityEvent, HipChatMessageBundle>();
		this.eventMap.put(TeamCityEvent.BUILD_STARTED, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.BUILD_STARTED, 
						HipChatEmoticonSet.POSITIVE, 
						HipChatMessageColour.INFO));
		this.eventMap.put(TeamCityEvent.BUILD_SUCCESSFUL, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.BUILD_SUCCESSFUL, 
						HipChatEmoticonSet.POSITIVE, 
						HipChatMessageColour.SUCCESS));
		this.eventMap.put(TeamCityEvent.BUILD_FAILED, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.BUILD_FAILED, 
						HipChatEmoticonSet.NEGATIVE, 
						HipChatMessageColour.ERROR));
		this.eventMap.put(TeamCityEvent.BUILD_INTERRUPTED, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.BUILD_INTERRUPTED, 
						HipChatEmoticonSet.INDIFFERENT, 
						HipChatMessageColour.WARNING));
		this.eventMap.put(TeamCityEvent.SERVER_STARTUP, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.SERVER_STARTUP, 
						null, 
						HipChatMessageColour.NEUTRAL));
		this.eventMap.put(TeamCityEvent.SERVER_SHUTDOWN, 
				new HipChatMessageBundle(HipChatNotificationMessageTemplate.SERVER_SHUTDOWN, 
						null, 
						HipChatMessageColour.NEUTRAL));
		this.emoticonCache = new HashMap<String, String>();
		logger.debug("Server extension created");
	}

	public void register() {
		logger.debug("Caching all available emoticons");

		int startIndex = 0;
		HipChatEmoticons emoticons = null;
		do {
			logger.debug(String.format("Start index: %s", startIndex));
			emoticons = this.processor.getEmoticons(startIndex);
			if (emoticons == null) {
				break;
			}
			for (HipChatEmoticon emoticon : emoticons.items) {
				logger.debug(String.format("Adding emoticon: %s - %s", emoticon.shortcut, emoticon.url));
				this.emoticonCache.put(emoticon.shortcut, emoticon.url);
			}
			startIndex = startIndex + emoticons.maxResults;
		} while (emoticons.links.next != null);
		
		this.server.addListener(this);
		logger.debug("Server extension registered");
	}

	@Override
	public void changesLoaded(SRunningBuild build) {
		logger.debug(String.format("Build started: %s", build.getBuildType().getName()));
		super.changesLoaded(build);
		if (this.configuration.getEvents() != null && this.configuration.getEvents().getBuildStartedStatus()) {
			this.processBuildEvent(build, TeamCityEvent.BUILD_STARTED);
		}
	}
	
	@Override
	public void buildFinished(SRunningBuild build) {
		super.buildFinished(build);
		if (build.getBuildStatus().isSuccessful() && this.configuration.getEvents() != null && this.configuration.getEvents().getBuildSuccessfulStatus()) {
			this.processBuildEvent(build, TeamCityEvent.BUILD_SUCCESSFUL);
		} else if (build.getBuildStatus().isFailed() && this.configuration.getEvents() != null && this.configuration.getEvents().getBuildFailedStatus()) {
			this.processBuildEvent(build, TeamCityEvent.BUILD_FAILED);
		}
	}
	
	@Override
	public void buildInterrupted(SRunningBuild build) {
		super.buildInterrupted(build);
		if (this.configuration.getEvents() != null && this.configuration.getEvents().getBuildInterruptedStatus()) {
			this.processBuildEvent(build, TeamCityEvent.BUILD_INTERRUPTED);
		}
	}
	
	@Override
	public void serverStartup() {
		if (this.configuration.getEvents() != null && this.configuration.getEvents().getServerStartupStatus()) {
			this.processServerEvent(TeamCityEvent.SERVER_STARTUP);
		}
	}

	@Override
	public void serverShutdown() {
		if (this.configuration.getEvents() != null && this.configuration.getEvents().getServerShutdownStatus()) {
			this.processServerEvent(TeamCityEvent.SERVER_SHUTDOWN);
		}
	}
	
	private void processServerEvent(TeamCityEvent event) {
		boolean notify = this.configuration.getDefaultNotifyStatus();
		HipChatMessageBundle bundle = this.eventMap.get(event);
		String colour = bundle.getColour();
		String message = bundle.getTemplate();
		HipChatRoomNotification notification = new HipChatRoomNotification(message, this.messageFormat, colour, notify);
		String roomId = this.configuration.getDefaultRoomId();
		if (roomId != null) {
			this.processor.sendNotification(notification, roomId);
		}
	}
	
	private void processBuildEvent(SRunningBuild build, TeamCityEvent event) {
		try {
			logger.info(String.format("Received %s build event", event));
			if (!this.configuration.getDisabledStatus() && !build.isPersonal()) {
				logger.info("Processing build event");
				String message = createHtmlBuildEventMessage(build, event);
				String colour = getBuildEventMessageColour(event);
				ProjectManager projectManager = this.server.getProjectManager();
				SProject project = projectManager.findProjectById(build.getProjectId());
				HipChatProjectConfiguration projectConfiguration = Utils.determineProjectConfiguration(project, configuration);
				HipChatRoomNotification notification = new HipChatRoomNotification(message, this.messageFormat, colour, projectConfiguration.getNotifyStatus());
				String roomId = projectConfiguration.getRoomId();
				logger.debug(String.format("Room to be notified: %s", roomId));
				if (!Utils.IsRoomIdNullOrNone(roomId)) {
					if (roomId.equals(HipChatConfiguration.ROOM_ID_DEFAULT_VALUE)) {
						roomId = configuration.getDefaultRoomId();
					} else if (roomId.equals(HipChatConfiguration.ROOM_ID_PARENT_VALUE)) {
						HipChatProjectConfiguration parentProjectConfiguration = Utils.findFirstSpecificParentConfiguration(project, configuration);
						if (parentProjectConfiguration != null) {
							logger.debug("Using specific configuration in hierarchy determined implicitly");
							roomId = parentProjectConfiguration.getRoomId();
							notification.notify = parentProjectConfiguration.getNotifyStatus();
						}
					}
					
					if (!Utils.IsRoomIdNullOrNone(roomId)) {
						logger.debug(String.format("Room notified: %s", roomId));
						this.processor.sendNotification(notification, roomId);
					}
				}
			}
		} catch (Exception e) {
			logger.error("Could not process build event", e);
		}
	}

	private String getBuildEventMessageColour(TeamCityEvent buildEvent) {
		return this.eventMap.get(buildEvent).getColour();
	}
		
	private String createHtmlBuildEventMessage(SRunningBuild build, TeamCityEvent buildEvent) throws TemplateException, IOException {	
		HipChatMessageBundle bundle = this.eventMap.get(buildEvent);
		Template template = createTemplate(bundle.getTemplate());
		
		// Emoticon
		String emoticon = getRandomEmoticon(bundle.getEmoticonSet());
		logger.debug(String.format("Emoticon: %s", emoticon));
		String emoticonUrl = this.emoticonCache.get(emoticon);

		// Branch
		Branch branch = build.getBranch();
		boolean hasBranch = branch != null;
		logger.debug(String.format("Has branch: %s", hasBranch));
		String branchDisplayName = "";
		if (hasBranch) {
			branchDisplayName = branch.getDisplayName();
			logger.debug(String.format("Branch: %s", branchDisplayName));
		}
		
		// Contributors (committers)
		String contributors = getContributors(build);
		boolean hasContributors = !contributors.isEmpty();
		logger.debug(String.format("Has contributors: %s", hasContributors));

		// Fill the template.
		Map<String, Object> templateMap = new HashMap<String, Object>();
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.EMOTICON_URL, emoticonUrl == null ? "" : emoticonUrl);		
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.FULL_NAME, build.getBuildType().getFullName());
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.TRIGGERED_BY, build.getTriggeredBy().getAsString());
	    templateMap.put(HipChatNotificationMessageTemplate.Attributes.HAS_CONTRIBUTORS, hasContributors);
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.CONTRIBUTORS, contributors);
	    templateMap.put(HipChatNotificationMessageTemplate.Attributes.HAS_BRANCH, hasBranch);
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.BRANCH, branchDisplayName);
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.SERVER_URL, this.server.getRootUrl());
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.PROJECT_ID, build.getProjectExternalId());
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.BUILD_ID, build.getBuildId());
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.BUILD_TYPE_ID, build.getBuildTypeId());
	    templateMap.put(HipChatNotificationMessageTemplate.Parameters.BUILD_NUMBER, build.getBuildNumber());
		if (buildEvent == TeamCityEvent.BUILD_INTERRUPTED) {
			long userId = build.getCanceledInfo().getUserId();
			SUser user = this.server.getUserModel().findUserById(userId);
			templateMap.put(HipChatNotificationMessageTemplate.Parameters.CANCELLED_BY, user.getDescriptiveName());
		}
		
		return renderTemplate(template, templateMap);
	}

	private static String getContributors(SBuild build) {
		UserSet<SUser> committers = build.getCommitters(SelectPrevBuildPolicy.SINCE_LAST_BUILD);	
		Collection<String> userSet = new HashSet<String>();
		for (SUser committer : committers.getUsers()) {
			userSet.add(committer.getDescriptiveName());
		}
		List<String> userList = new ArrayList<String>(userSet);
		Collections.sort(userList, String.CASE_INSENSITIVE_ORDER);
		String contributors = Utils.join(userList);
		return contributors;
	}
	
	private static Template createTemplate(String templateString) throws IOException {
		String templateName = "template";
		StringTemplateLoader loader = new StringTemplateLoader();
		loader.putTemplate(templateName, templateString);
		Configuration config = new Configuration();
		config.setTemplateLoader(loader);
		return config.getTemplate(templateName);
	}
	
	private static String renderTemplate(Template template, Map<String, Object> templateMap) throws TemplateException, IOException {
		Writer writer = new StringWriter();
	    template.process(templateMap, writer);
	    writer.flush();
	    String renderedTemplate = writer.toString();
	    writer.close();
	    return renderedTemplate;		
	}
	
	private static String getRandomEmoticon(String[] set) {
		int i = rng.nextInt(set.length);
		return set[i];
	}

}
