/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.closures.drivers.DriverConstants;
import com.vmware.admiral.closures.services.adapter.AdmiralAdapterFactoryService;
import com.vmware.admiral.closures.services.closure.Closure;
import com.vmware.admiral.closures.services.closure.ClosureFactoryService;
import com.vmware.admiral.closures.services.closuredescription.ClosureDescription;
import com.vmware.admiral.closures.services.closuredescription.ClosureDescriptionFactoryService;
import com.vmware.admiral.closures.services.closuredescription.ResourceConstraints;
import com.vmware.admiral.closures.util.ClosureProps;
import com.vmware.admiral.common.DeploymentProfileConfig;
import com.vmware.admiral.common.test.BaseTestCase;
import com.vmware.admiral.common.test.HostInitTestDcpServicesConfig;
import com.vmware.admiral.common.util.ConfigurationUtil;
import com.vmware.admiral.compute.ComputeConstants;
import com.vmware.admiral.compute.container.GroupResourcePlacementService;
import com.vmware.admiral.host.interceptor.OperationInterceptorRegistry;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.service.common.ConfigurationService;
import com.vmware.admiral.service.common.ConfigurationService.ConfigurationState;
import com.vmware.admiral.service.test.MockComputeHostInstanceAdapter;
import com.vmware.admiral.service.test.MockDockerHostAdapterImageService;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.common.test.TestContext;

public class ClosureServiceRequestsTest extends BaseTestCase {

    private static final int DEFAULT_OPERATION_TIMEOUT = 30;

    private ComputeDescriptionService.ComputeDescription hostDesc;

    private final Object initializationLock = new Object();

    @Before
    public void setUp() throws Exception {
        try {
            this.host.log(Level.INFO, "Starting test services ...");
            startCoreServices(this.host);
            this.host.log(Level.INFO, "Test services started.");

            // turn on powershell runtime
            configurePullFromRegistry(DriverConstants.RUNTIME_POWERSHELL_6,
                    "powershell.test.registry");

            startClosureServices(this.host);
            waitForServiceAvailability(AdmiralAdapterFactoryService.FACTORY_LINK);
            this.host
                    .log(Level.INFO, "Service ready: " + AdmiralAdapterFactoryService.FACTORY_LINK);
            waitForInitialBootServiceToBeSelfStopped(ComputeInitialBootService.SELF_LINK);
            this.host.log(Level.INFO, "Initial Boot Service stopped: " + ComputeInitialBootService
                    .SELF_LINK);

            // first docker host
            ComputeDescriptionService.ComputeDescription dockerHostDesc = createDockerHostDescription();
            createDockerHost(dockerHostDesc);

            // second docker host
            ComputeDescriptionService.ComputeDescription dockerHostDescSecond =
                    createDockerHostDescription();
            createDockerHost(dockerHostDescSecond);

            this.host.log(Level.INFO, "Test compute created.");

        } catch (Throwable e) {
            this.host.log(Level.SEVERE, "Failed to run test setup. Reason: %s", Utils.toString
                    (e));
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void registerInterceptors(OperationInterceptorRegistry registry) {
        CompositeComponentInterceptor.register(registry);
    }

    @Test
    public void executeJSNumberParametersWithImageLoadTest() throws Throwable {
        // Load image from admiral or docker hub
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, null);

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.source =
                "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                        expectedOutVar
                        + "; result = test(inputs.a);";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FINISHED,
                                    finalClosureResponse[0].state);

                            assertEquals(expectedInVar,
                                    finalClosureResponse[0].inputs.get("a").getAsInt());
                            assertEquals(expectedResult,
                                    finalClosureResponse[0].outputs.get("a").getAsInt(), 0);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeImageLoadFailureTest() throws Throwable {
        // Load image from admiral or docker hub
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, null);

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.source =
                "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                        expectedOutVar
                        + "; result = test(inputs.a);";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);

        // Simulate image load failure
        closureState.customProperties = new HashMap<>();
        closureState.customProperties.put(MockDockerHostAdapterImageService.FAILURE_EXPECTED,
                "true");
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FAILED,
                                    finalClosureResponse[0].state);

                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeJSNumberParametersWithConfiguredPlacementTest() throws Throwable {
        // Load image from admiral or docker hub
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, null);

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.source =
                "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                        expectedOutVar
                        + "; result = test(inputs.a);";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        closureDefState.customProperties = new HashMap<>();
        // configure placement
        closureDefState.customProperties.put(ClosureProps.CUSTOM_PROPERTY_PLACEMENT,
                GroupResourcePlacementService.DEFAULT_RESOURCE_PLACEMENT_LINK);
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FINISHED,
                                    finalClosureResponse[0].state);

                            assertEquals(expectedInVar,
                                    finalClosureResponse[0].inputs.get("a").getAsInt());
                            assertEquals(expectedResult,
                                    finalClosureResponse[0].outputs.get("a").getAsInt(), 0);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeJSNumberParametersWithImageCreateTest() throws Throwable {
        // Load image from private registry
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, "private.registry.url");

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.source =
                "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                        expectedOutVar
                        + "; result = test(inputs.a);";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FINISHED,
                                    finalClosureResponse[0].state);

                            assertEquals(expectedInVar,
                                    finalClosureResponse[0].inputs.get("a").getAsInt());
                            assertEquals(expectedResult,
                                    finalClosureResponse[0].outputs.get("a").getAsInt(), 0);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeJSNumberParametersWithImageCreateExternalSourceTest() throws Throwable {
        // Load image from private registry
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, "private.registry.url");

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.sourceURL = "http://faked_source_url";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FAILED,
                                    finalClosureResponse[0].state);

                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeImageCreateFailureTest() throws Throwable {
        // Load image from private registry
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, "private.registry.url");

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.source =
                "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                        expectedOutVar
                        + "; result = test(inputs.a);";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        // Simulate image load failure
        closureState.customProperties = new HashMap<>();
        closureState.customProperties.put(MockDockerHostAdapterImageService.FAILURE_EXPECTED,
                "true");
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FAILED,
                                    finalClosureResponse[0].state);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executeImageCreateFailureExtSourceTest() throws Throwable {
        // Load image from private registry
        configurePullFromRegistry(DriverConstants.RUNTIME_NODEJS_4, "private.registry.url");

        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 3;

        closureDefState.sourceURL = "http://faked_source_url";
        closureDefState.runtime = DriverConstants.RUNTIME_NODEJS_4;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        // Simulate image load failure
        closureState.customProperties = new HashMap<>();
        closureState.customProperties.put(MockDockerHostAdapterImageService.FAILURE_EXPECTED,
                "true");
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FAILED,
                                    finalClosureResponse[0].state);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    @Test
    public void executePowershellNumberParametersWithImageLoadTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils
                .buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        TestContext ctx = testCreate(1);

        ClosureDescription closureDefState = new ClosureDescription();
        closureDefState.name = "test";

        int expectedInVar = 3;
        double expectedResult = 3;

        closureDefState.source = "$result = $inputs.a + 1\n"
                + "$context.outputs = @{\"result\" = $result} | ConvertTo-JSON\n"
                + "Add-Content 'output.txt' $context.outputs\n";

        closureDefState.runtime = DriverConstants.RUNTIME_POWERSHELL_6;
        closureDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        closureDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        closureDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI closureDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(closureDefState)
                .setCompletion((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    ctx.completeIteration();
                });
        this.host.send(post);
        ctx.await();

        // Create Closure
        TestContext ctx1 = testCreate(1);
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        //        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/"
                        + closureDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI closureChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation closurePost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskState.TaskStage.CREATED, closureResponses[0].state);
                    ctx1.completeIteration();
                });
        this.host.send(closurePost);
        ctx1.await();

        // Executing the created Closure
        TestContext ctx2 = testCreate(1);
        Closure closureRequest = new Closure();
        Map<String, JsonElement> inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation closureExecPost = Operation
                .createPost(closureChildURI)
                .setBody(closureRequest)
                .setCompletion((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                    ctx2.completeIteration();
                });
        this.host.send(closureExecPost);
        ctx2.await();

        // Wait for the completion timeout
        waitForCompletion(closureState.documentSelfLink, DEFAULT_OPERATION_TIMEOUT);

        final Closure[] finalClosureResponse = new Closure[1];
        TestContext ctx3 = testCreate(1);
        Operation closureGet = Operation
                .createGet(closureChildURI)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        ctx3.failIteration(e);
                    } else {
                        try {
                            finalClosureResponse[0] = o.getBody(Closure.class);
                            assertEquals(closureState.descriptionLink,
                                    finalClosureResponse[0].descriptionLink);
                            assertEquals(TaskState.TaskStage.FINISHED,
                                    finalClosureResponse[0].state);

                            assertEquals(expectedInVar,
                                    finalClosureResponse[0].inputs.get("a").getAsInt());
                            assertEquals(expectedResult,
                                    finalClosureResponse[0].outputs.get("a").getAsInt(), 0);
                            ctx3.completeIteration();
                        } catch (Throwable ex) {
                            ctx3.failIteration(ex);
                        }
                    }
                });
        this.host.send(closureGet);
        ctx3.await();

        clean(closureChildURI);
        clean(closureDefChildURI);
    }

    private static void startCoreServices(ServiceHost serviceHost) throws Throwable {
        DeploymentProfileConfig.getInstance().setTest(true);
        HostInitPhotonModelServiceConfig.startServices(serviceHost);
        HostInitTestDcpServicesConfig.startServices(serviceHost);
        HostInitCommonServiceConfig.startServices(serviceHost);
        HostInitComputeServicesConfig.startServices(serviceHost, true);
        HostInitRequestServicesConfig.startServices(serviceHost);
        HostInitDockerAdapterServiceConfig.startServices(serviceHost, true);
    }

    private static void startClosureServices(ServiceHost serviceHost) throws Throwable {
        HostInitClosureServiceConfig.startServices(serviceHost, true);
    }

    private static void configurePullFromRegistry(String runtime, String registryUrl) {
        ConfigurationState[] configs = ConfigurationService.getConfigurationProperties();

        List<ConfigurationState> newConfigs = Arrays.stream(configs).collect(Collectors.toList());
        ConfigurationState confState = findConfigPropertyState(newConfigs, runtime);
        if (confState != null) {
            confState.value = registryUrl;
        } else {
            confState = new ConfigurationState();
            confState.key = ClosureProps.CLOSURE_RUNTIME_IMAGE_REGISTRY + runtime;
            confState.value = registryUrl;
            newConfigs.add(confState);
        }

        ConfigurationUtil.initialize(newConfigs.toArray(new ConfigurationState[newConfigs.size()]));
    }

    private static ConfigurationState findConfigPropertyState(List<ConfigurationState>
            configurations, String runtime) {
        for (ConfigurationState state : configurations) {
            if (state.key.equalsIgnoreCase(ClosureProps.CLOSURE_RUNTIME_IMAGE_REGISTRY + runtime)) {
                return state;
            }
        }

        return null;
    }

    private Closure getClosure(String closureLink) throws InterruptedException, ExecutionException,
            TimeoutException {

        CompletableFuture<Operation> c = new CompletableFuture<Operation>();

        TestContext ctx = testCreate(1);
        URI closureUri = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureLink);
        Operation closureGet = Operation
                .createGet(closureUri)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        c.completeExceptionally(ex);
                        ctx.failIteration(ex);
                    } else {
                        c.complete(o);
                        ctx.completeIteration();
                    }

                });

        //        this.host.testStart(1);

        this.host.send(closureGet);
        ctx.await();

        return c.get(2000, TimeUnit.MILLISECONDS).getBody(Closure.class);
    }

    private void clean(URI childURI) throws Throwable {
        TestContext ctx = testCreate(1);
        CompletableFuture<Operation> c = new CompletableFuture<Operation>();
        Operation delete = Operation
                .createDelete(childURI)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        c.completeExceptionally(ex);
                        ctx.failIteration(ex);
                    } else {
                        c.complete(o);
                        ctx.completeIteration();
                    }
                });

        this.host.send(delete);
        ctx.await();

        c.get(5000, TimeUnit.MILLISECONDS);
    }

    private void waitForCompletion(String closureLink, int timeout)
            throws Exception {
        Closure fetchedClosure = getClosure(closureLink);
        long startTime = System.currentTimeMillis();
        while (!isCompleted(fetchedClosure) && !isTimeoutElapsed(startTime, timeout)) {
            try {
                Thread.sleep(500);
                fetchedClosure = getClosure(closureLink);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isCompleted(Closure fetchedClosure) {
        return TaskState.TaskStage.CREATED != fetchedClosure.state
                && TaskState.TaskStage.STARTED != fetchedClosure.state;
    }

    private boolean isTimeoutElapsed(long startTime, int timeout) {
        return System.currentTimeMillis() - startTime > TimeUnit.SECONDS.toMillis(timeout);
    }

    protected ComputeService.ComputeState createDockerHost(
            ComputeDescriptionService.ComputeDescription computeDesc) throws Throwable {
        synchronized (initializationLock) {
            return createDockerHost(computeDesc, true);
        }
    }

    protected ComputeService.ComputeState createDockerHost(
            ComputeDescriptionService.ComputeDescription computeDesc, boolean generateId)
            throws Throwable {
        ComputeService.ComputeState containerHost = TestRequestStateFactory
                .createDockerComputeHost();
        if (generateId) {
            containerHost.id = UUID.randomUUID().toString();
        }
        containerHost.documentSelfLink = containerHost.id;
        containerHost.resourcePoolLink = GroupResourcePlacementService.DEFAULT_RESOURCE_POOL_LINK;
        containerHost.tenantLinks = computeDesc.tenantLinks;
        containerHost.descriptionLink = computeDesc.documentSelfLink;
        containerHost.endpointLink = computeDesc.endpointLink;
        containerHost.powerState = ComputeService.PowerState.ON;

        if (containerHost.customProperties == null) {
            containerHost.customProperties = new HashMap<>();
        }

        containerHost.customProperties.put(ComputeConstants.COMPUTE_CONTAINER_HOST_PROP_NAME,
                "true");

        containerHost.customProperties.put(ComputeConstants.GROUP_RESOURCE_PLACEMENT_LINK_NAME,
                GroupResourcePlacementService.DEFAULT_RESOURCE_PLACEMENT_LINK);

        containerHost = getOrCreateDocument(containerHost, ComputeService.FACTORY_LINK);
        assertNotNull(containerHost);

        return containerHost;
    }

    protected ComputeDescriptionService.ComputeDescription createDockerHostDescription()
            throws Throwable {
        synchronized (initializationLock) {
            if (hostDesc == null) {
                hostDesc = TestRequestStateFactory.createDockerHostDescription();
                hostDesc.instanceAdapterReference = UriUtils.buildUri(host,
                        MockComputeHostInstanceAdapter.SELF_LINK);
                hostDesc.endpointLink = null;
                hostDesc = doPost(hostDesc,
                        ComputeDescriptionService.FACTORY_LINK);
                assertNotNull(hostDesc);
            }
            return hostDesc;
        }
    }

}
