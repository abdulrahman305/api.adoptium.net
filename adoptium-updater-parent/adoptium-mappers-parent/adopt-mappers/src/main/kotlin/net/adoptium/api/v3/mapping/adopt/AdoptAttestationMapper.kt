package net.adoptium.api.v3.mapping.adopt

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import net.adoptium.api.v3.dataSources.github.GitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAttestation
import net.adoptium.api.v3.models.Attestation
import net.adoptium.api.v3.models.Architecture
import net.adoptium.api.v3.models.ImageType
import net.adoptium.api.v3.models.JvmImpl
import net.adoptium.api.v3.models.OperatingSystem
import net.adoptium.api.v3.models.Vendor
import net.adoptium.api.v3.mapping.AttestationMapper
import org.slf4j.LoggerFactory
import java.util.EnumMap


@ApplicationScoped
open class AdoptAttestationMapperFactory @Inject constructor(
    val htmlClient: GitHubHtmlClient
) {
    private val mappers: MutableMap<Vendor, AdoptAttestationMapper> = EnumMap(Vendor::class.java)

    open fun get(vendor: Vendor): AttestationMapper {
        return if (mappers.containsKey(vendor)) {
            mappers[vendor]!!
        } else {
            val mapper = AdoptAttestationMapper(htmlClient)
            mappers[vendor] = mapper
            mapper
        }
    }
}

private class AdoptAttestationMapper(
    val htmlClient: GitHubHtmlClient
) : AttestationMapper() {
    companion object {
        @JvmStatic
        private val LOGGER = LoggerFactory.getLogger(this::class.java)
    }

    override suspend fun toAttestationList(vendor: Vendor, ghAttestationAssets: Map<String, GHAttestation>): List<Attestation> {
        return ghAttestationAssets
            .map { (key, value) -> assetToAttestationAsync(vendor, key, value) }
            .map { it.await() }
    }

    override suspend fun toAttestation(vendor: Vendor, attestation_link: String, ghAttestation: GHAttestation): Attestation {
        return assetToAttestationAsync(vendor, attestation_link, ghAttestation).await()
    }

    private fun assetToAttestationAsync(
        vendor: Vendor,
        attestation_link: String,
        ghAttestationAsset: GHAttestation
    ): Deferred<Attestation> {
        return GlobalScope.async {
                // Temurin Attestations (https://github.com/adoptium/temurin-attestations/blob/main/.github/workflows/validate-cdxa.yml) have:
                //   ONE attestation
                //   ONE target component
                //   ONE assessor
                //   ONE claim
                //   ONE target component externalReferences reference with a single hash
                //   target component version is of format jdk-$MAJOR_VERSION+$BUILD_NUM or jdk-$MAJOR_VERSION.0.$UPDATE_VERSION+$BUILD_NUM
                
                val releaseName: String? = ghAttestationAsset?.declarations?.targets?.components?.component[0]?.version
                // featureVersion derived from releaseName
                val featureVersion: Int? = releaseName?.split("-","+",".")[1]?.toInt()

                val assessor_org: String? = ghAttestationAsset?.declarations?.assessors?.assessor[0]?.organization?.name
                val assessor_affirmation: String? = ghAttestationAsset?.declarations?.affirmation?.statement
                val assessor_claim_predicate: String? = ghAttestationAsset?.declarations?.claims?.claim[0]?.predicate
                val target_checksum: String? = ghAttestationAsset?.declarations?.targets?.components?.component[0]?.externalReferences?.reference[0]?.hashes?.hash[0]?.sha256

                var archStr: String = ""
                var osStr: String = ""
                for (property in ghAttestationAsset?.declarations?.targets?.components?.component[0]?.properties?.property?: emptyList()) {
                    if (property.name == "platform") {
                        val split_platform: List<String>? = property.value?.split("_")
                        if (split_platform != null) {
                            archStr = split_platform[0]
                            osStr   = split_platform[1]
                        }
                    }
                }

                val arch: Architecture  = Architecture.valueOf(archStr)  //by lazy { Architecture.valueOf(archStr) }
                val os: OperatingSystem = OperatingSystem.valueOf(osStr) //by lazy { OperatingSystem.valueOf(osStr) }
 
                return@async Attestation(ghAttestationAsset.id.toString()?:"", ghAttestationAsset.commitResourcePath?:"", ghAttestationAsset.filename?:"",
                                         featureVersion, releaseName, os, arch, ImageType.jdk, JvmImpl.hotspot,
                                         vendor, target_checksum, assessor_org, assessor_affirmation, assessor_claim_predicate,
                                         attestation_link, attestation_link+".sign.pub")
        }
    }
}
