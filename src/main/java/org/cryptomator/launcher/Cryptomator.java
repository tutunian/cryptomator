/*******************************************************************************
 * Copyright (c) 2017 Skymatic UG (haftungsbeschränkt).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE file.
 *******************************************************************************/
package org.cryptomator.launcher;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dagger.Lazy;
import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.common.Environment;
import org.cryptomator.common.ShutdownHook;
import org.cryptomator.ipc.IpcCommunicator;
import org.cryptomator.logging.DebugMode;
import org.cryptomator.logging.LoggerConfiguration;
import org.cryptomator.ui.fxapp.FxApplicationComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javafx.application.Application;
import javafx.stage.Stage;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

@Singleton
public class Cryptomator {

	private static final long STARTUP_TIME = System.currentTimeMillis();
	// DaggerCryptomatorComponent gets generated by Dagger.
	// Run Maven and include target/generated-sources/annotations in your IDE.
	private static final CryptomatorComponent CRYPTOMATOR_COMPONENT = DaggerCryptomatorComponent.factory().create(STARTUP_TIME);
	private static final Logger LOG = LoggerFactory.getLogger(Cryptomator.class);

	private final LoggerConfiguration logConfig;
	private final DebugMode debugMode;
	private final SupportedLanguages supportedLanguages;
	private final Environment env;
	private final Lazy<IpcMessageHandler> ipcMessageHandler;
	private final ShutdownHook shutdownHook;

	@Inject
	Cryptomator(LoggerConfiguration logConfig, DebugMode debugMode, SupportedLanguages supportedLanguages, Environment env, Lazy<IpcMessageHandler> ipcMessageHandler, ShutdownHook shutdownHook) {
		this.logConfig = logConfig;
		this.debugMode = debugMode;
		this.supportedLanguages = supportedLanguages;
		this.env = env;
		this.ipcMessageHandler = ipcMessageHandler;
		this.shutdownHook = shutdownHook;
	}

	public static void main(String[] args) {
		var printVersion = Optional.ofNullable(args) //
				.stream() //Streams either one element (the args-array) or zero elements
				.flatMap(Arrays::stream) //
				.anyMatch(arg -> "-v".equals(arg) || "--version".equals(arg));

		if (printVersion) {
			var appVer = System.getProperty("cryptomator.appVersion", "SNAPSHOT");
			var buildNumber = System.getProperty("cryptomator.buildNumber", "SNAPSHOT");

			//Reduce noise for parsers by using System.out directly
			System.out.printf("Cryptomator version %s (build %s)%n", appVer, buildNumber);
			return;
		}

		int exitCode = CRYPTOMATOR_COMPONENT.application().run(args);
		LOG.info("Exit {}", exitCode);
		System.exit(exitCode); // end remaining non-daemon threads.
	}

	/**
	 * Main entry point of the application launcher.
	 *
	 * @param args The arguments passed to this program via {@link #main(String[])}.
	 * @return Nonzero exit code in case of an error.
	 */
	private int run(String[] args) {
		logConfig.init();
		LOG.debug("Dagger graph initialized after {}ms", System.currentTimeMillis() - STARTUP_TIME);
		LOG.info("Starting Cryptomator {} on {} {} ({})", env.getAppVersion(), SystemUtils.OS_NAME, SystemUtils.OS_VERSION, SystemUtils.OS_ARCH);
		debugMode.initialize();
		supportedLanguages.applyPreferred();

		/*
		 * Attempts to create an IPC connection to a running Cryptomator instance and sends it the given args.
		 * If no external process could be reached, the args will be handled by the loopback IPC endpoint.
		 */
		try (var communicator = IpcCommunicator.create(env.ipcSocketPath().toList())) {
			if (communicator.isClient()) {
				communicator.sendHandleLaunchargs(List.of(args));
				communicator.sendRevealRunningApp();
				LOG.info("Found running application instance. Shutting down...");
				return 0;
			} else {
				shutdownHook.runOnShutdown(communicator::closeUnchecked);
				var executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("IPC-%d").build());
				var msgHandler = ipcMessageHandler.get();
				msgHandler.handleLaunchArgs(List.of(args));
				communicator.listen(msgHandler, executor);
				LOG.debug("Did not find running application instance. Launching GUI...");
				return runGuiApplication();
			}
		} catch (Throwable e) {
			LOG.error("Running application failed", e);
			return 1;
		}
	}

	/**
	 * Launches the JavaFX application, blocking the main thread until shuts down.
	 *
	 * @return Nonzero exit code in case of an error.
	 */
	private int runGuiApplication() {
		try {
			Application.launch(MainApp.class);
			LOG.info("UI shut down");
			return 0;
		} catch (Exception e) {
			LOG.error("Terminating due to error", e);
			return 1;
		}
	}

	public static class MainApp extends Application {

		@Override
		public void start(Stage primaryStage) {
			LOG.info("JavaFX runtime started after {}ms", System.currentTimeMillis() - STARTUP_TIME);
			FxApplicationComponent component = CRYPTOMATOR_COMPONENT.fxAppComponentBuilder() //
					.fxApplication(this) //
					.primaryStage(primaryStage) //
					.build();
			component.application().start();
		}

		@Override
		public void stop() {
			LOG.info("JavaFX application stopped.");
		}

	}

}
