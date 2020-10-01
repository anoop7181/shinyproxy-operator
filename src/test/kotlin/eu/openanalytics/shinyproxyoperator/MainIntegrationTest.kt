package eu.openanalytics.shinyproxyoperator

import eu.openanalytics.shinyproxyoperator.helpers.IntegrationTestBase
import eu.openanalytics.shinyproxyoperator.helpers.ShinyProxyTestInstance
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MainIntegrationTest : IntegrationTestBase() {

    @Test
    fun `ingress should not be created before ReplicaSet is ready`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        spTestInstance.create()

        // 2. prepare the operator
        operator.prepare()

        // 3. check whether the controller waits until the ReplicaSet is ready before creating other resources
        var checked = false
        while (true) {
            // let the operator handle one event
            operator.shinyProxyController.receiveAndHandleEvent()

            val replicaSets = namespacedClient.apps().replicaSets().list().items
            if (replicaSets.size == 0) {
                // if replicaset is not created -> continue handling events
                continue
            }
            assertEquals(1, replicaSets.size)
            val replicaSet = replicaSets[0]
            // replicaset exists -> perform our checks, operator is paused

            if (!Readiness.isReady(replicaSet)) {
                // replicaset not ready
                // -> Service should not yet be created
                assertEquals(0, namespacedClient.services().list().items.size)

                // -> Ingress should not yet be created
                assertEquals(0, namespacedClient.network().ingresses().list().items.size)

                // -> Latest marker should not yet be set
                val sp = spTestInstance.retrieveInstance()
                assertEquals(1, sp.status.instances.size)
                println("${sp.status.instances[0].isLatestInstance} => ${Readiness.isReady(replicaSet)}")
                assertFalse(sp.status.instances[0].isLatestInstance)
                checked = true
            } else {
                // ReplicaSet is Ready -> break
                break
            }
        }

        assertTrue(checked) // actually checked that ingress wasn't created when the ReplicaSet wasn't ready yet

        val job = GlobalScope.launch {
            // let the operator finish its business
            operator.run()
        }

        // 4. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 5. assert correctness
        spTestInstance.assertInstanceIsCorrect()
        job.cancel()
    }

    @Test
    fun simple_test() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 4. assert correctness
        spTestInstance.assertInstanceIsCorrect()
        job.cancel()
    }


    @Test
    fun `operator should re-create removed resources`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()

        // 4. assert correctness
        spTestInstance.assertInstanceIsCorrect()

        // 5. Delete Replicaset -> reconcile -> assert it is still ok
        namespacedClient.apps().replicaSets().withName("sp-${sp.metadata.name}-rs-${spTestInstance.hash}".take(63)).delete()
        spTestInstance.waitForOneReconcile()
        spTestInstance.assertInstanceIsCorrect()

        // 6. Delete ConfigMap -> reconcile -> assert it is still ok
        namespacedClient.configMaps().withName("sp-${sp.metadata.name}-cm-${spTestInstance.hash}".take(63)).delete()
        spTestInstance.waitForOneReconcile()
        spTestInstance.assertInstanceIsCorrect()

        // 7. Delete Service -> reconcile -> assert it is still ok
        namespacedClient.services().withName("sp-${sp.metadata.name}-svc-${spTestInstance.hash}".take(63)).delete()
        spTestInstance.waitForOneReconcile()
        spTestInstance.assertInstanceIsCorrect()

        // 8. Delete Ingress -> reconcile -> assert it is still ok
        namespacedClient.network().ingress().withName("sp-${sp.metadata.name}-ing-${spTestInstance.hash}".take(63)).delete()
        spTestInstance.waitForOneReconcile()
        spTestInstance.assertInstanceIsCorrect()

        job.cancel()
    }

    @Test
    fun `sp in other namespaced should be ignored when using namespaced mode`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance in other namespace
        val spTestInstance = ShinyProxyTestInstance("itest-2", namespacedClient.inNamespace("itest-2"), shinyProxyClient, "simple_config.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait a bit
        delay(20000)

        // assert that there are no ReplicaSets created
        assertEquals(0, namespacedClient.apps().replicaSets().list().items.size)

        job.cancel()
    }

    @Test
    fun `simple test with PodTemplateSpec patches`() = setup { namespace, shinyProxyClient, namespacedClient, operator, reconcileListener ->
        // 1. create a SP instance in other namespace
        val spTestInstance = ShinyProxyTestInstance(namespace, namespacedClient, shinyProxyClient, "simple_config_with_patches.yaml", reconcileListener)
        val sp = spTestInstance.create()

        // 2. start the operator and let it do it's work
        val job = GlobalScope.launch {
            operator.prepare()
            operator.run()
        }

        // 3. wait until instance is created
        spTestInstance.waitForOneReconcile()


        // 4. assertions
        val retrievedSp = spTestInstance.retrieveInstance()
        assertNotNull(retrievedSp)
        val instance = retrievedSp.status.instances[0]
        assertNotNull(instance)
        assertTrue(instance.isLatestInstance)

        // check configmap
        spTestInstance.assertConfigMapIsCorrect(sp)

        // check replicaset
        val replicaSets = namespacedClient.inNamespace(namespace).apps().replicaSets().list().items
        assertEquals(1, replicaSets.size)
        val replicaSet = replicaSets[0]
        val templateSpec = replicaSet.spec.template.spec
        assertEquals(1, templateSpec.containers.size)
        assertEquals("shinyproxy", templateSpec.containers[0].name)

        assertEquals(1, templateSpec.containers[0].livenessProbe.periodSeconds)
        assertEquals(30, templateSpec.containers[0].livenessProbe.initialDelaySeconds) // changed by patch
        assertEquals("/actuator/health/liveness", templateSpec.containers[0].livenessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].livenessProbe.httpGet.port)

        assertEquals(1, templateSpec.containers[0].readinessProbe.periodSeconds)
        assertEquals(30, templateSpec.containers[0].readinessProbe.initialDelaySeconds) // changed by patch
        assertEquals("/actuator/health/readiness", templateSpec.containers[0].readinessProbe.httpGet.path)
        assertEquals(IntOrString(8080), templateSpec.containers[0].readinessProbe.httpGet.port)

        assertEquals(null, templateSpec.containers[0].startupProbe) // changed by patch

        assertEquals(3, templateSpec.containers[0].env.size) // changed by patch
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_UID" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "SP_KUBE_POD_NAME" })
        assertNotNull(templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" })
        assertEquals("TEST_VALUE", templateSpec.containers[0].env.firstOrNull { it.name == "TEST_VAR" }?.value)

        // check service
        spTestInstance.assertServiceIsCorrect(sp)

        // check ingress
        spTestInstance.assertIngressIsCorrect(sp)

        job.cancel()
    }


}