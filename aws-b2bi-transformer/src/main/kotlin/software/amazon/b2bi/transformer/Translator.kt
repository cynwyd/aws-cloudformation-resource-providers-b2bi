package software.amazon.b2bi.transformer

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.b2bi.model.AccessDeniedException
import software.amazon.awssdk.services.b2bi.model.ConflictException
import software.amazon.awssdk.services.b2bi.model.CreateTransformerRequest
import software.amazon.awssdk.services.b2bi.model.DeleteTransformerRequest
import software.amazon.awssdk.services.b2bi.model.FileFormat
import software.amazon.awssdk.services.b2bi.model.GetTransformerRequest
import software.amazon.awssdk.services.b2bi.model.GetTransformerResponse
import software.amazon.awssdk.services.b2bi.model.InternalServerException
import software.amazon.awssdk.services.b2bi.model.ListTransformersRequest
import software.amazon.awssdk.services.b2bi.model.ListTransformersResponse
import software.amazon.awssdk.services.b2bi.model.ResourceNotFoundException
import software.amazon.awssdk.services.b2bi.model.ServiceQuotaExceededException
import software.amazon.awssdk.services.b2bi.model.TagResourceRequest
import software.amazon.awssdk.services.b2bi.model.ThrottlingException
import software.amazon.awssdk.services.b2bi.model.TransformerStatus
import software.amazon.awssdk.services.b2bi.model.UntagResourceRequest
import software.amazon.awssdk.services.b2bi.model.UpdateTransformerRequest
import software.amazon.awssdk.services.b2bi.model.ValidationException
import software.amazon.b2bi.transformer.TagHelper.toSdkTag
import software.amazon.cloudformation.exceptions.BaseHandlerException
import software.amazon.cloudformation.exceptions.CfnAccessDeniedException
import software.amazon.cloudformation.exceptions.CfnAlreadyExistsException
import software.amazon.cloudformation.exceptions.CfnGeneralServiceException
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException
import software.amazon.cloudformation.exceptions.CfnNotFoundException
import software.amazon.cloudformation.exceptions.CfnServiceInternalErrorException
import software.amazon.cloudformation.exceptions.CfnServiceLimitExceededException
import software.amazon.cloudformation.exceptions.CfnThrottlingException
import java.time.Instant
import software.amazon.awssdk.services.b2bi.model.EdiType as SdkEdi
import software.amazon.awssdk.services.b2bi.model.X12Details as SdkX12
import software.amazon.b2bi.transformer.EdiType as ResourceEdi
import software.amazon.b2bi.transformer.X12Details as ResourceX12

/**
 * This class is a centralized placeholder for
 * - api request construction
 * - object translation to/from aws sdk
 * - resource model construction for read/list handlers
 */
object Translator {
    /**
     * Request to create a resource
     * @param model resource model
     * @return createTransformerRequest the aws service request to create a resource
     */
    fun translateToCreateRequest(model: ResourceModel): CreateTransformerRequest {
        return CreateTransformerRequest.builder()
            .name(model.name)
            .fileFormat(model.fileFormat)
            .mappingTemplate(model.mappingTemplate)
            .sampleDocument(model.sampleDocument)
            .tags(model.tags.map { it.toSdkTag() })
            .ediType(model.ediType.translateToSdkEdi())
            .build()
    }

    /**
     * Request to read a resource
     * @param model resource model
     * @return GetTransformerRequest the aws service request to describe a resource
     */
    fun translateToReadRequest(model: ResourceModel): GetTransformerRequest {
        return GetTransformerRequest.builder()
            .transformerId(model.transformerId)
            .build()
    }

    /**
     * Translates resource object from sdk into a resource model
     * @param response the aws service describe resource response
     * @return model resource model
     */
    fun translateFromReadResponse(response: GetTransformerResponse): ResourceModel {
        return ResourceModel.builder()
            .transformerId(response.transformerId().emptyToNull())
            .transformerArn(response.transformerArn().emptyToNull())
            .name(response.name().emptyToNull())
            .fileFormat(response.fileFormat().emptyToNull())
            .mappingTemplate(response.mappingTemplate().emptyToNull())
            .sampleDocument(response.sampleDocument().emptyToNull())
            .ediType(response.ediType()?.translateToResourceEdi())
            .status(response.status().emptyToNull())
            .createdAt(response.createdAt().emptyToNull())
            .modifiedAt(response.modifiedAt().emptyToNull())
            .build()
    }

    private fun String?.emptyToNull() = if (this.isNullOrEmpty()) null else this

    private fun Instant?.emptyToNull() = if (this == null) null else this.toString()

    private fun FileFormat?.emptyToNull() =  if (this == null) null else this.toString()

    private fun TransformerStatus?.emptyToNull() = if (this == null) null else this.toString()

    /**
     * Request to delete a resource
     * @param model resource model
     * @return awsRequest the aws service request to delete a resource
     */
    fun translateToDeleteRequest(model: ResourceModel): DeleteTransformerRequest {
        return DeleteTransformerRequest.builder()
            .transformerId(model.transformerId)
            .build()
    }

    /**
     * Request to update properties of a previously created resource
     * @param model resource model
     * @return awsRequest the aws service request to modify a resource
     */
    fun translateToUpdateRequest(model: ResourceModel): UpdateTransformerRequest {
        return UpdateTransformerRequest.builder()
            .transformerId(model.transformerId)
            .name(model.name)
            .ediType(model.ediType.translateToSdkEdi())
            .fileFormat(model.fileFormat)
            .mappingTemplate(model.mappingTemplate)
            .sampleDocument(model.sampleDocument)
            .status(model.status)
            .build()
    }

    /**
     * Request to list resources
     * @param nextToken token passed to the aws service list resources request
     * @return awsRequest the aws service request to list resources within aws account
     */
    fun translateToListRequest(nextToken: String?): ListTransformersRequest {
        return ListTransformersRequest.builder()
            .apply {
                nextToken?.let { nextToken(it) }
            }
            .build()
    }

    /**
     * Translates resource objects from sdk into a resource model (primary identifier only)
     * @param response the aws service describe resource response
     * @return list of resource models
     */
    fun translateFromListResponse(response: ListTransformersResponse): List<ResourceModel> {
        return response.transformers().map {
            ResourceModel.builder()
                .transformerId(it.transformerId())
                .name(it.name())
                .fileFormat(it.fileFormatAsString())
                .mappingTemplate(it.mappingTemplate())
                .sampleDocument(it.sampleDocument())
                .status(it.statusAsString())
                .createdAt(it.createdAt().toString())
                .modifiedAt(if (it.modifiedAt() != null) it.modifiedAt().toString() else null)
                .build()
        }
    }

    /**
     * Request to add tags to a resource
     * @param model resource model
     * @return awsRequest the aws service request to create a resource
     */
    fun translateToTagResourceRequest(model: ResourceModel, addedTags: Map<String, String>): TagResourceRequest {
        return TagResourceRequest.builder()
            .resourceARN(model.transformerArn)
            .tags(TagHelper.convertToList(addedTags).map { it.toSdkTag() })
            .build()
    }

    /**
     * Request to add tags to a resource
     * @param model resource model
     * @param removedTags tags to remove
     * @return untagResourceRequest the aws service request to create a resource
     */
    fun translateToUntagResourceRequest(model: ResourceModel, removedTags: Set<String>): UntagResourceRequest {
        return UntagResourceRequest.builder()
            .resourceARN(model.transformerArn)
            .tagKeys(removedTags)
            .build()
    }

    fun ResourceEdi.translateToSdkEdi(): SdkEdi {
        val x12 : SdkX12 = SdkX12.builder().transactionSet(this.x12Details.transactionSet).version(this.x12Details.version).build()
        return SdkEdi.builder().x12Details(x12).build()
    }

    fun SdkEdi.translateToResourceEdi(): ResourceEdi {
        val x12 : ResourceX12 = ResourceX12.builder().transactionSet(this.x12Details().transactionSetAsString()).version(this.x12Details().versionAsString()).build()
        return ResourceEdi.builder().x12Details(x12).build()
    }

    fun AwsServiceException.toCfnException(): BaseHandlerException = when (this) {
        is AccessDeniedException -> CfnAccessDeniedException(this)
        is ConflictException -> CfnAlreadyExistsException(this)
        is InternalServerException -> CfnServiceInternalErrorException(this)
        is ResourceNotFoundException -> CfnNotFoundException(this)
        is ServiceQuotaExceededException -> CfnServiceLimitExceededException(this)
        is ThrottlingException -> CfnThrottlingException(this)
        is ValidationException -> CfnInvalidRequestException(this)
        else -> CfnGeneralServiceException(this)
    }
}
