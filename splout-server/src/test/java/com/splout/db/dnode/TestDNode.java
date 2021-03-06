package com.splout.db.dnode;

/*
 * #%L
 * Splout SQL Server
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import junit.framework.Assert;
import junit.framework.AssertionFailedError;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.splout.db.common.JSONSerDe;
import com.splout.db.common.SploutConfiguration;
import com.splout.db.common.TestUtils;
import com.splout.db.dnode.beans.DNodeSystemStatus;
import com.splout.db.thrift.DNodeService;
import com.splout.db.thrift.DeployAction;
import com.splout.db.thrift.PartitionMetadata;

/**
 * Basic DNode tests
 */
public class TestDNode {

	@After
	@Before
	public void cleanUp() throws IOException {
		FileUtils.deleteDirectory(new File(DB_1 + ".2"));
		FileUtils.deleteDirectory(new File(DB_2 + ".2"));
		FileUtils.deleteDirectory(new File(DB_1 + ".1"));
		FileUtils.deleteDirectory(new File(DB_2 + ".1"));
		FileUtils.deleteDirectory(new File(FOO_DEPLOY_FOLDER));
		TestUtils.cleanUpTmpFolders(this.getClass().getName(), 4);
	}

	public static String FOO_DEPLOY_FOLDER = TestDNode.class.getName() + "-foo-deploy";
	public static String DB_1 = TestDNode.class.getName() + ".db.1";
	public static String DB_2 = TestDNode.class.getName() + ".db.2";

	protected void waitForDeployToFinish(DNodeService.Client client) throws Exception {
		DNodeSystemStatus status = JSONSerDe.deSer(client.status(), DNodeSystemStatus.class);
		while(status.isDeployInProgress()) {
			Thread.sleep(10);
			status = JSONSerDe.deSer(client.status(), DNodeSystemStatus.class);
		}
	}

	@Test
	public void testDeployTimeout() throws Throwable {
		SploutConfiguration testConfig = SploutConfiguration.getTestConfig();
		// Set deploy timeout to something very low
		testConfig.setProperty(DNodeProperties.DEPLOY_TIMEOUT_SECONDS, 1);
		//
		DNodeHandler dHandler = new DNodeHandler(new Fetcher(testConfig) {
			
			@Override
			public File fetch(String uriStr) throws IOException, URISyntaxException {
				try {
	        Thread.sleep(5000);
	        return null;
        } catch(InterruptedException e) {
	        throw new IOException(e);
        }
			}
		}); 
		DNode dnode = TestUtils.getTestDNode(testConfig, dHandler, "dnode-" + this.getClass().getName() + "-1");
		//
		DNodeService.Client client = DNodeClient.get("localhost", testConfig.getInt(DNodeProperties.PORT));

		try {
			DeployAction deploy = new DeployAction();
			deploy.setTablespace("tablespace3");
			deploy.setDataURI("file:///foo");
			deploy.setPartition(0);
			deploy.setVersion(1l);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 1l);
			waitForDeployToFinish(client);

			Assert.assertEquals(true, dHandler.lastDeployTimedout.get());
			Assert.assertEquals(false, dHandler.deployInProgress.get() > 0);
		} finally {
			DNodeClient.close(client);
			//
			dnode.stop();
		}
	}

	@Test
	public void testPoolCacheEviction() throws Throwable {
		TestUtils.createFooDatabase(DB_1 + ".1", 1, "foo1");
		TestUtils.createFooDatabase(DB_2 + ".1", 2, "foo2");

		SploutConfiguration testConfig = SploutConfiguration.getTestConfig();
		// Set cache eviction to something quite low
		testConfig.setProperty(DNodeProperties.EH_CACHE_SECONDS, 1);
		//
		DNodeHandler dHandler = new DNodeHandler();
		DNode dnode = TestUtils.getTestDNode(testConfig, dHandler, "dnode-" + this.getClass().getName() + "-2");
		//
		DNodeService.Client client = DNodeClient.get("localhost", testConfig.getInt(DNodeProperties.PORT));

		try {
			DeployAction deploy = new DeployAction();
			deploy.setTablespace("tablespace1");
			deploy.setDataURI(new File(DB_1 + ".1", "foo.db").toURI().toString());
			deploy.setPartition(0);
			deploy.setVersion(1l);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 1l);
			
			Thread.sleep(1000);
			waitForDeployToFinish(client);

			Thread.sleep(200);
			
			Assert.assertEquals(false, dHandler.lastDeployTimedout.get());
			Assert.assertEquals(false, dHandler.deployInProgress.get() > 0);

			client.sqlQuery("tablespace1", 1l, 0, "SELECT 1;");
			Assert.assertEquals(dHandler.dbCache.getKeysWithExpiryCheck().size(), 1);

			deploy = new DeployAction();
			deploy.setTablespace("tablespace1");
			deploy.setDataURI(new File(DB_2 + ".1", "foo.db").toURI().toString());
			deploy.setPartition(0);
			deploy.setVersion(2l);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 2l);
			
			Thread.sleep(1000);
			waitForDeployToFinish(client);

			Assert.assertEquals(false, dHandler.lastDeployTimedout.get());
			Assert.assertEquals(false, dHandler.deployInProgress.get() > 0);

			client.sqlQuery("tablespace1", 2l, 0, "SELECT 1;");
			client.sqlQuery("tablespace1", 1l, 0, "SELECT 1;");
			Assert.assertEquals(dHandler.dbCache.getKeysWithExpiryCheck().size(), 2);

			long weAreWaitingSoFar = 0;
			while(dHandler.dbCache.getKeysWithExpiryCheck().size() > 1) {
				Thread.sleep(10);
				weAreWaitingSoFar += 10;
				if(weAreWaitingSoFar > 2000) {
					throw new AssertionFailedError("Waiting more than twice the specified eviction time for pool cache entries.");
				}
			}
		} finally {
			DNodeClient.close(client);
			//
			dnode.stop();
		}
	}

	// In this test we are deploying two versions of the same tablespace and querying both
	@SuppressWarnings("rawtypes")
	@Test
	public void testQueryVersioning() throws Throwable {
		TestUtils.createFooDatabase(DB_1 + ".2", 1, "foo1");
		TestUtils.createFooDatabase(DB_2 + ".2", 2, "foo2");

		SploutConfiguration testConfig = SploutConfiguration.getTestConfig();
		DNodeHandler dHandler = new DNodeHandler();
		DNode dnode = TestUtils.getTestDNode(testConfig, dHandler, "dnode-" + this.getClass().getName() + "-3");
		//
		DNodeService.Client client = DNodeClient.get("localhost", testConfig.getInt(DNodeProperties.PORT));

		try {
			DeployAction deploy = new DeployAction();
			deploy.setTablespace("tablespace1");
			deploy.setDataURI(new File(DB_1 + ".2", "foo.db").toURI().toString());
			deploy.setVersion(1l);
			deploy.setPartition(0);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 1l);
			waitForDeployToFinish(client);

			Thread.sleep(200);
			
			Assert.assertEquals(false, dHandler.lastDeployTimedout.get());
			Assert.assertEquals(false, dHandler.deployInProgress.get() > 0);

			deploy = new DeployAction();
			deploy.setTablespace("tablespace1");
			deploy.setDataURI(new File(DB_2 + ".2", "foo.db").toURI().toString());
			deploy.setVersion(2l);
			deploy.setPartition(0);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 2l);
			waitForDeployToFinish(client);

			ArrayList resultsV1 = JSONSerDe.deSer(client.sqlQuery("tablespace1", 1l, 0, "SELECT * FROM t;"),
			    ArrayList.class);
			ArrayList resultsV2 = JSONSerDe.deSer(client.sqlQuery("tablespace1", 2l, 0, "SELECT * FROM t;"),
			    ArrayList.class);
			Assert.assertEquals(((Map) resultsV1.get(0)).get("b"), "foo1");
			Assert.assertEquals(((Map) resultsV2.get(0)).get("b"), "foo2");
		} finally {
			DNodeClient.close(client);
			//
			dnode.stop();
		}
	}

	// simple test asserting that deploy finishes correctly and the data is there
	@Test
	public void testLocalDeploy() throws Throwable {
		SploutConfiguration testConfig = SploutConfiguration.getTestConfig();
		String dataFolder = "dnode-" + this.getClass().getName() + "-4";

		DNodeHandler dHandler = new DNodeHandler();
		DNode dnode = TestUtils.getTestDNode(testConfig, dHandler, dataFolder);
		//
		DNodeService.Client client = DNodeClient.get("localhost", testConfig.getInt(DNodeProperties.PORT));
		try {
			DeployAction deploy = new DeployAction();
			deploy.setTablespace("tablespace2");
			TestUtils.createFooDatabase(FOO_DEPLOY_FOLDER, 1, "foo");
			deploy.setDataURI(new File(FOO_DEPLOY_FOLDER, "foo.db").toURI().toString());
			deploy.setPartition(0);
			deploy.setVersion(1l);
			deploy.setMetadata(new PartitionMetadata());
			client.deploy(Arrays.asList(new DeployAction[] { deploy }), 1l);
			waitForDeployToFinish(client);
			Thread.sleep(200);
			File expectedDataFolder = dHandler.getLocalStorageFolder("tablespace2", 0, 1);
			Assert.assertTrue(expectedDataFolder.exists());
			Assert.assertTrue(new File(expectedDataFolder, "foo.db").exists());
		} finally {
			DNodeClient.close(client);
			//
			dnode.stop();
		}
	}
}
