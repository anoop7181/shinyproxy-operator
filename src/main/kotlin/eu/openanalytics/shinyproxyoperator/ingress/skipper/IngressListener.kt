package eu.openanalytics.shinyproxyoperator.ingress.skipper

import eu.openanalytics.shinyproxyoperator.components.LabelFactory
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEventType
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.networking.v1beta1.Ingress
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

// TODO this has some duplicate code with ResourceListener<T>
class IngressListener(private val channel: SendChannel<ShinyProxyEvent>,
                      private val kubernetesClient: KubernetesClient,
                      informer: SharedIndexInformer<Ingress>,
                      private val shinyProxyLister: Lister<ShinyProxy>) {

    private val logger = KotlinLogging.logger {}

    init {
        informer.addEventHandler(object : ResourceEventHandler<Ingress> {
            override fun onAdd(resource: Ingress) {
                logger.debug { "${resource.kind}::OnAdd ${resource.metadata.name}" }
                runBlocking { enqueuResource(resource) }
            }

            override fun onUpdate(resource: Ingress, newResource: Ingress) {
                logger.debug { "${resource.kind}::OnUpdate ${resource.metadata.name}" }
                runBlocking { enqueuResource(resource) }
            }

            override fun onDelete(resource: Ingress, b: Boolean) {
                logger.debug { "${resource.kind}::OnDelete ${resource.metadata.name}" }
                try {
                    runBlocking { enqueuResource(resource) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private suspend fun enqueuResource(resource: Ingress) {
        val replicaSetOwnerReference = getShinyProxyOwnerRefByKind(resource, "ReplicaSet") ?: return
        // TODO namespace
        val replicaSet = kubernetesClient.apps().replicaSets().inNamespace("default").withName(replicaSetOwnerReference.name).get()
        if (replicaSet == null) {
            logger.warn { "Cannot find ReplicaSet (owner) of resource ${resource.kind}/${resource.metadata.name}, probably the resource is being deleted." }
            return
        }
        val ownerReference = getShinyProxyOwnerRefByKind(replicaSet, "ShinyProxy") ?: return

        val shinyProxy = shinyProxyLister[ownerReference.name] ?: return
        val hashOfInstance = resource.metadata.labels[LabelFactory.INSTANCE_LABEL]
        if (hashOfInstance == null) {
            logger.warn { "Cannot find hash of instance for resource ${resource.kind}/${resource.metadata.name}, probably some labels are wrong." }
            return
        }

        val shinyProxyInstance = shinyProxy.status.getInstanceByHash(hashOfInstance)
        if (shinyProxyInstance == null) {
            logger.warn { "Cannot find instance based on hash for resource ${resource.kind}/${resource.metadata.name}, probably some labels are wrong." }
            return
        }

        channel.send(ShinyProxyEvent(ShinyProxyEventType.RECONCILE, shinyProxy, shinyProxyInstance))
    }


    private fun getShinyProxyOwnerRefByKind(resource: HasMetadata, kind: String): OwnerReference? {
        val ownerReferences = resource.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.kind.toLowerCase() == kind.toLowerCase()) {
                return ownerReference
            }
        }

        return null
    }

}