package com.power.doc.template;

import com.power.common.util.*;
import com.power.doc.builder.ProjectDocConfigBuilder;
import com.power.doc.constants.*;
import com.power.doc.handler.IHeaderHandler;
import com.power.doc.handler.IRequestMappingHandler;
import com.power.doc.helper.FormDataBuildHelper;
import com.power.doc.helper.JsonBuildHelper;
import com.power.doc.helper.ParamsBuildHelper;
import com.power.doc.model.*;
import com.power.doc.model.annotation.EntryAnnotation;
import com.power.doc.model.annotation.FrameworkAnnotations;
import com.power.doc.model.annotation.MappingAnnotation;
import com.power.doc.model.request.ApiRequestExample;
import com.power.doc.model.request.CurlRequest;
import com.power.doc.model.request.RequestMapping;
import com.power.doc.utils.*;
import com.thoughtworks.qdox.model.*;
import com.thoughtworks.qdox.model.expression.AnnotationValue;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.power.doc.constants.DocGlobalConstants.FILE_CONTENT_TYPE;
import static com.power.doc.constants.DocGlobalConstants.JSON_CONTENT_TYPE;
import static com.power.doc.constants.DocTags.IGNORE;
import static com.power.doc.constants.DocTags.IGNORE_REQUEST_BODY_ADVICE;

public interface IRestDocTemplate extends IBaseDocBuildTemplate {

    Logger log = Logger.getLogger(IRestDocTemplate.class.getName());
    AtomicInteger atomicInteger = new AtomicInteger(1);

    default List<ApiDoc> processApiData(ProjectDocConfigBuilder projectBuilder, FrameworkAnnotations frameworkAnnotations,
                                        List<ApiReqParam> configApiReqParams, IRequestMappingHandler baseMappingHandler, IHeaderHandler headerHandler) {
        ApiConfig apiConfig = projectBuilder.getApiConfig();
        List<ApiDoc> apiDocList = new ArrayList<>();
        int order = 0;
        boolean setCustomOrder = false;
        Collection<JavaClass> classes = projectBuilder.getJavaProjectBuilder().getClasses();
        // exclude  class is ignore
        for (JavaClass cls : classes) {
            if (StringUtil.isNotEmpty(apiConfig.getPackageFilters())) {
                // from smart config
                if (!DocUtil.isMatch(apiConfig.getPackageFilters(), cls.getCanonicalName())) {
                    continue;
                }
            }
            // from tag
            DocletTag ignoreTag = cls.getTagByName(DocTags.IGNORE);
            if (!defaultEntryPoint(cls, frameworkAnnotations) || Objects.nonNull(ignoreTag)) {
                continue;
            }
            String strOrder = JavaClassUtil.getClassTagsValue(cls, DocTags.ORDER, Boolean.TRUE);
            order++;
            if (ValidateUtil.isNonnegativeInteger(strOrder)) {
                setCustomOrder = true;
                order = Integer.parseInt(strOrder);
            }
            List<ApiMethodDoc> apiMethodDocs = buildEntryPointMethod(cls, apiConfig, projectBuilder,
                    frameworkAnnotations, configApiReqParams, baseMappingHandler, headerHandler);
            this.handleApiDoc(cls, apiDocList, apiMethodDocs, order, apiConfig.isMd5EncryptedHtmlName());
        }
        apiDocList = handleTagsApiDoc(apiDocList);
        if (apiConfig.isSortByTitle()) {
            Collections.sort(apiDocList);
        } else if (setCustomOrder) {
            // while set custom oder
            return apiDocList.stream()
                    .sorted(Comparator.comparing(ApiDoc::getOrder))
                    .peek(p -> p.setOrder(atomicInteger.getAndAdd(1))).collect(Collectors.toList());
        }
        return apiDocList;
    }

    default String createDocRenderHeaders(List<ApiReqParam> headers, boolean isAdoc) {
        StringBuilder builder = new StringBuilder();
        if (CollectionUtil.isEmpty(headers)) {
            headers = new ArrayList<>(0);
        }
        for (ApiReqParam header : headers) {
            if (isAdoc) {
                builder.append("|");
            }
            builder.append(header.getName()).append("|")
                    .append(header.getType()).append("|")
                    .append(header.isRequired()).append("|")
                    .append(header.getDesc()).append("|")
                    .append(header.getSince()).append("\n");
        }
        return builder.toString();
    }


    default void handleApiDoc(JavaClass cls, List<ApiDoc> apiDocList, List<ApiMethodDoc> apiMethodDocs, int order, boolean isUseMD5) {
        String controllerName = cls.getName();
        ApiDoc apiDoc = new ApiDoc();
        String classAuthor = JavaClassUtil.getClassTagsValue(cls, DocTags.AUTHOR, Boolean.TRUE);
        apiDoc.setOrder(order);
        apiDoc.setName(controllerName);
        apiDoc.setAuthor(classAuthor);
        apiDoc.setAlias(controllerName);
        apiDoc.setFolder(true);
        apiDoc.setPackageName(cls.getPackage().getName());
        //apiDoc.setAuthor();

        // handle class tags
        List<DocletTag> classTags = cls.getTagsByName(DocTags.TAG);
        apiDoc.setTags(classTags.stream().map(DocletTag::getValue).toArray(String[]::new));

        if (isUseMD5) {
            String name = DocUtil.generateId(apiDoc.getName());
            apiDoc.setAlias(name);
        }
        String desc = DocUtil.getEscapeAndCleanComment(cls.getComment());
        apiDoc.setDesc(desc);
        apiDoc.setList(apiMethodDocs);
        apiDocList.add(apiDoc);
    }


    default Set<String> ignoreParamsSets(JavaMethod method) {
        Set<String> ignoreSets = new HashSet<>();
        DocletTag ignoreParam = method.getTagByName(DocTags.IGNORE_PARAMS);
        if (Objects.nonNull(ignoreParam)) {
            String[] igParams = ignoreParam.getValue().split(" ");
            Collections.addAll(ignoreSets, igParams);
        }
        return ignoreSets;
    }

    default void mappingParamToApiParam(String str, List<ApiParam> paramList, Map<String, String> mappingParams) {
        String param = StringUtil.removeQuotes(str);
        String paramName;
        String paramValue;

        if (param.contains("=")) {
            int index = param.indexOf("=");
            paramName = param.substring(0, index);
            paramValue = param.substring(index + 1);
        } else {
            paramName = param;
            paramValue = DocUtil.getValByTypeAndFieldName("string", paramName, Boolean.TRUE);
        }
        String type = ValidateUtil.isPositiveInteger(paramValue) ? "int32" : "string";
        ApiParam apiParam = ApiParam.of().setField(paramName)
                .setId(paramList.size() + 1)
                .setQueryParam(true)
                .setValue(paramValue)
                .setType(type).setDesc("parameter condition")
                .setRequired(true)
                .setVersion(DocGlobalConstants.DEFAULT_VERSION);
        paramList.add(apiParam);
        mappingParams.put(paramName, null);
    }

    default void mappingParamProcess(String str, Map<String, String> pathParamsMap) {
        String param = StringUtil.removeQuotes(str);
        String paramName;
        String paramValue;
        if (param.contains("=")) {
            int index = param.indexOf("=");
            paramName = param.substring(0, index);
            paramValue = param.substring(index + 1);
            pathParamsMap.put(paramName, paramValue);
        } else {
            paramName = param;
            pathParamsMap.put(paramName, DocUtil.getValByTypeAndFieldName("string", paramName, Boolean.TRUE));
        }
    }

    default String getRewriteClassName(Map<String, String> replacementMap, String fullTypeName, String commentClass) {
        String rewriteClassName;
        if (Objects.nonNull(commentClass) && !DocGlobalConstants.NO_COMMENTS_FOUND.equals(commentClass)) {
            String[] comments = commentClass.split("\\|");
            if (comments.length < 1) {
                return replacementMap.get(fullTypeName);
            }
            rewriteClassName = comments[comments.length - 1];
            if (JavaClassValidateUtil.isClassName(rewriteClassName)) {
                return rewriteClassName;
            }
        }
        return replacementMap.get(fullTypeName);
    }

    default String getParamName(String paramName, JavaAnnotation annotation) {
        String resolvedParamName = DocUtil.resolveAnnotationValue(annotation.getProperty(DocAnnotationConstants.VALUE_PROP));
        if (StringUtils.isBlank(resolvedParamName)) {
            resolvedParamName = DocUtil.resolveAnnotationValue(annotation.getProperty(DocAnnotationConstants.NAME_PROP));
        }
        if (!StringUtils.isBlank(resolvedParamName)) {
            paramName = StringUtil.removeQuotes(resolvedParamName);
        }
        return StringUtil.removeQuotes(paramName);
    }

    default List<ApiDoc> handleTagsApiDoc(List<ApiDoc> apiDocList) {
        if (CollectionUtil.isEmpty(apiDocList)) {
            return Collections.emptyList();
        }

        // all class tag copy
        Map<String, ApiDoc> copyMap = new HashMap<>();
        apiDocList.forEach(doc -> {
            String[] tags = doc.getTags();
            if (ArrayUtils.isEmpty(tags)) {
                tags = new String[]{doc.getName()};
            }

            for (String tag : tags) {
                tag = StringUtil.trim(tag);
                copyMap.computeIfPresent(tag, (k, v) -> {
                    List<ApiMethodDoc> list = CollectionUtil.isEmpty(v.getList()) ? new ArrayList<>() : v.getList();
                    list.addAll(doc.getList());
                    v.setList(list);
                    return v;
                });
                copyMap.putIfAbsent(tag, doc);
            }
        });

        // handle method tag
        Map<String, ApiDoc> allMap = new HashMap<>(copyMap);
        allMap.forEach((k, v) -> {
            List<ApiMethodDoc> methodDocList = v.getList();
            methodDocList.forEach(method -> {
                String[] tags = method.getTags();
                if (ArrayUtils.isEmpty(tags)) {
                    return;
                }
                for (String tag : tags) {
                    tag = StringUtil.trim(tag);
                    copyMap.computeIfPresent(tag, (k1, v2) -> {
                        method.setOrder(v2.getList().size() + 1);
                        v2.getList().add(method);
                        return v2;
                    });
                    copyMap.putIfAbsent(tag, ApiDoc.buildTagApiDoc(v, tag, method));
                }
            });
        });

        List<ApiDoc> apiDocs = new ArrayList<>(copyMap.values());
        int index = apiDocs.size() - 1;
        for (ApiDoc apiDoc : apiDocs) {
            if (apiDoc.getOrder() == null) {
                apiDoc.setOrder(index++);
            }
        }
        apiDocs.sort(Comparator.comparing(ApiDoc::getOrder));
        return apiDocs;
    }

    default List<JavaAnnotation> getClassAnnotations(JavaClass cls, FrameworkAnnotations frameworkAnnotations) {
        List<JavaAnnotation> annotationsList = new ArrayList<>(cls.getAnnotations());
        Map<String, EntryAnnotation> mappingAnnotationMap = frameworkAnnotations.getEntryAnnotations();
        boolean flag = annotationsList.stream().anyMatch(item -> {
            String annotationName = item.getType().getValue();
            String fullyName = item.getType().getFullyQualifiedName();
            return mappingAnnotationMap.containsKey(annotationName) || mappingAnnotationMap.containsKey(fullyName);
        });
        // child override parent set
        if (flag) {
            return annotationsList;
        }
        JavaClass superJavaClass = cls.getSuperJavaClass();
        if (Objects.nonNull(superJavaClass) && !"Object".equals(superJavaClass.getSimpleName())) {
            annotationsList.addAll(getClassAnnotations(superJavaClass, frameworkAnnotations));
        }
        return annotationsList;
    }

    default List<ApiMethodDoc> buildEntryPointMethod(final JavaClass cls, ApiConfig apiConfig,
                                                     ProjectDocConfigBuilder projectBuilder, FrameworkAnnotations frameworkAnnotations,
                                                     List<ApiReqParam> configApiReqParams, IRequestMappingHandler baseMappingHandler, IHeaderHandler headerHandler) {
        String clazName = cls.getCanonicalName();
        boolean paramsDataToTree = projectBuilder.getApiConfig().isParamsDataToTree();
        String group = JavaClassUtil.getClassTagsValue(cls, DocTags.GROUP, Boolean.TRUE);
        String classAuthor = JavaClassUtil.getClassTagsValue(cls, DocTags.AUTHOR, Boolean.TRUE);
        List<JavaAnnotation> classAnnotations = this.getClassAnnotations(cls, frameworkAnnotations);
        String baseUrl = "";
        Map<String, MappingAnnotation> mappingAnnotationMap = frameworkAnnotations.getMappingAnnotations();
        for (JavaAnnotation annotation : classAnnotations) {
            String annotationName = annotation.getType().getValue();
            MappingAnnotation mappingAnnotation = mappingAnnotationMap.get(annotationName);
            if (Objects.isNull(mappingAnnotation)) {
                continue;
            }
            if (CollectionUtil.isNotEmpty(mappingAnnotation.getPathProps())) {
                baseUrl = StringUtil.removeQuotes(DocUtil.getPathUrl(annotation, mappingAnnotation.getPathProps()
                        .toArray(new String[0])));
            }
        }

        List<JavaMethod> methods = cls.getMethods();
        List<DocJavaMethod> docJavaMethods = new ArrayList<>(methods.size());
        for (JavaMethod method : methods) {
            if (method.isPrivate() || DocUtil.isMatch(apiConfig.getPackageExcludeFilters(), clazName + "." + method.getName())) {
                continue;
            }
            docJavaMethods.add(DocJavaMethod.builder().setJavaMethod(method));
        }
        JavaClass parentClass = cls.getSuperJavaClass();
        if (Objects.nonNull(parentClass) && !"Object".equals(parentClass.getSimpleName())) {
            Map<String, JavaType> actualTypesMap = JavaClassUtil.getActualTypesMap(parentClass);
            List<JavaMethod> parentMethodList = parentClass.getMethods();
            for (JavaMethod method : parentMethodList) {
                docJavaMethods.add(DocJavaMethod.builder().setJavaMethod(method).setActualTypesMap(actualTypesMap));
            }
        }
        List<JavaType> implClasses = cls.getImplements();
        for (JavaType type : implClasses) {
            JavaClass javaClass = (JavaClass) type;
            Map<String, JavaType> actualTypesMap = JavaClassUtil.getActualTypesMap(javaClass);
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.isDefault()) {
                    docJavaMethods.add(DocJavaMethod.builder().setJavaMethod(method).setActualTypesMap(actualTypesMap));
                }
            }
        }
        List<ApiMethodDoc> methodDocList = new ArrayList<>(methods.size());
        int methodOrder = 0;
        for (DocJavaMethod docJavaMethod : docJavaMethods) {
            JavaMethod method = docJavaMethod.getJavaMethod();
            if (method.isPrivate() || Objects.nonNull(method.getTagByName(IGNORE))) {
                continue;
            }
            //handle request mapping
            RequestMapping requestMapping = baseMappingHandler.handle(projectBuilder, baseUrl,
                    method, frameworkAnnotations,
                    (javaClass, mapping) -> this.requestMappingPostProcess(javaClass, method, mapping));
            if (Objects.isNull(requestMapping)) {
                continue;
            }
            if(Objects.isNull(requestMapping.getShortUrl())) {
                continue;
            }
             if (StringUtil.isEmpty(method.getComment()) && apiConfig.isStrict()) {
                throw new RuntimeException("Unable to find comment for method " + method.getName() + " in " + cls.getCanonicalName());
            }
            ApiMethodDoc apiMethodDoc = new ApiMethodDoc();
            DocletTag downloadTag = method.getTagByName(DocTags.DOWNLOAD);
            if (Objects.nonNull(downloadTag)) {
                apiMethodDoc.setDownload(true);
            }
            DocletTag pageTag = method.getTagByName(DocTags.PAGE);
            if (Objects.nonNull(pageTag)) {
                String pageUrl = projectBuilder.getServerUrl() + "/" + pageTag.getValue();
                apiMethodDoc.setPage(UrlUtil.simplifyUrl(pageUrl));
            }
            DocletTag docletTag = method.getTagByName(DocTags.GROUP);
            if (Objects.nonNull(docletTag)) {
                apiMethodDoc.setGroup(docletTag.getValue());
            } else {
                apiMethodDoc.setGroup(group);
            }

            // handle tags
            List<DocletTag> tags = method.getTagsByName(DocTags.TAG);
            apiMethodDoc.setTags(tags.stream().map(DocletTag::getValue).toArray(String[]::new));

            methodOrder++;
            apiMethodDoc.setOrder(methodOrder);
            apiMethodDoc.setName(method.getName());
            apiMethodDoc.setDesc(method.getComment());
            String methodUid = DocUtil.generateId(clazName + method.getName() + methodOrder);
            apiMethodDoc.setMethodId(methodUid);
            String apiNoteValue = DocUtil.getNormalTagComments(method, DocTags.API_NOTE, cls.getName());
            if (StringUtil.isEmpty(apiNoteValue)) {
                apiNoteValue = method.getComment();
            }
            Map<String, String> authorMap = DocUtil.getCommentsByTag(method, DocTags.AUTHOR, cls.getName());
            String authorValue = String.join(", ", new ArrayList<>(authorMap.keySet()));
            if (apiConfig.isShowAuthor() && StringUtil.isNotEmpty(authorValue)) {
                apiMethodDoc.setAuthor(StringUtil.removeQuotes(authorValue));
            }
            if (apiConfig.isShowAuthor() && StringUtil.isEmpty(authorValue)) {
                apiMethodDoc.setAuthor(classAuthor);
            }
            apiMethodDoc.setDetail(apiNoteValue != null ? apiNoteValue : "");
            //handle headers
            List<ApiReqParam> apiReqHeaders = headerHandler.handle(method, projectBuilder);
            apiReqHeaders = apiReqHeaders.stream().filter(param -> DocUtil.filterPath(requestMapping, param)).collect(Collectors.toList());

            apiMethodDoc.setType(requestMapping.getMethodType());
            apiMethodDoc.setUrl(requestMapping.getUrl());
            apiMethodDoc.setServerUrl(projectBuilder.getServerUrl());
            apiMethodDoc.setPath(requestMapping.getShortUrl());
            apiMethodDoc.setDeprecated(requestMapping.isDeprecated());

            final List<ApiReqParam> apiReqParamList = configApiReqParams.stream()
                    .filter(param -> DocUtil.filterPath(requestMapping, param)).collect(Collectors.toList());

            ApiMethodReqParam apiMethodReqParam = requestParams(docJavaMethod, projectBuilder, apiReqParamList, frameworkAnnotations);

            // build request params
            if (paramsDataToTree) {
                apiMethodDoc.setPathParams(ApiParamTreeUtil.apiParamToTree(apiMethodReqParam.getPathParams()));
                apiMethodDoc.setQueryParams(ApiParamTreeUtil.apiParamToTree(apiMethodReqParam.getQueryParams()));
                apiMethodDoc.setRequestParams(ApiParamTreeUtil.apiParamToTree(apiMethodReqParam.getRequestParams()));
            } else {
                apiMethodDoc.setPathParams(apiMethodReqParam.getPathParams());
                apiMethodDoc.setQueryParams(apiMethodReqParam.getQueryParams());
                apiMethodDoc.setRequestParams(apiMethodReqParam.getRequestParams());
            }

            List<ApiReqParam> allApiReqHeaders;
            final Map<String, List<ApiReqParam>> reqParamMap = configApiReqParams.stream().collect(Collectors.groupingBy(ApiReqParam::getParamIn));
            final List<ApiReqParam> headerParamList = reqParamMap.getOrDefault(ApiReqParamInTypeEnum.HEADER.getValue(), Collections.emptyList());
            allApiReqHeaders = Stream.of(headerParamList, apiReqHeaders).filter(Objects::nonNull)
                    .flatMap(Collection::stream).distinct().filter(param -> DocUtil.filterPath(requestMapping, param)).collect(Collectors.toList());

            //reduce create in template
            apiMethodDoc.setHeaders(this.createDocRenderHeaders(allApiReqHeaders, apiConfig.isAdoc()));
            apiMethodDoc.setRequestHeaders(allApiReqHeaders);
            String path = apiMethodDoc.getPath().split(";")[0];
            String pathUrl = DocUtil.formatPathUrl(path);
            List<ApiParam> pathParams = apiMethodDoc.getPathParams();
            Iterator<ApiParam> pathIterator = pathParams.iterator();
            while (pathIterator.hasNext()) {
                ApiParam next = pathIterator.next();
                String pathKey = "{" + next.getField() + "}";
                if (!pathUrl.contains(pathKey)) {
                    pathIterator.remove();
                }
            }

            // build request json
            ApiRequestExample requestExample = buildReqJson(docJavaMethod, apiMethodDoc, requestMapping.getMethodType(),
                    projectBuilder, frameworkAnnotations);
            String requestJson = requestExample.getExampleBody();
            // set request example detail
            apiMethodDoc.setRequestExample(requestExample);
            apiMethodDoc.setRequestUsage(requestJson == null ? requestExample.getUrl() : requestJson);
            // build response usage
            String responseValue = DocUtil.getNormalTagComments(method, DocTags.API_RESPONSE, cls.getName());
            if (StringUtil.isNotEmpty(responseValue)) {
                responseValue = responseValue.replaceAll("<br>", "");
                apiMethodDoc.setResponseUsage(JsonUtil.toPrettyFormat(responseValue));
            } else {
                apiMethodDoc.setResponseUsage(JsonBuildHelper.buildReturnJson(docJavaMethod, projectBuilder));
            }
            // build response params
            List<ApiParam> responseParams = buildReturnApiParams(docJavaMethod, projectBuilder);
            if (paramsDataToTree) {
                responseParams = ApiParamTreeUtil.apiParamToTree(responseParams);
            }
            apiMethodDoc.setReturnSchema(docJavaMethod.getReturnSchema());
            apiMethodDoc.setRequestSchema(docJavaMethod.getRequestSchema());
            apiMethodDoc.setResponseParams(responseParams);

            TornaUtil.setTornaArrayTags(apiMethodDoc);
            methodDocList.add(apiMethodDoc);
        }


        return methodDocList;
    }

    default ApiRequestExample buildReqJson(DocJavaMethod javaMethod, ApiMethodDoc apiMethodDoc, String methodType,
                                           ProjectDocConfigBuilder configBuilder, FrameworkAnnotations frameworkAnnotations) {
        JavaMethod method = javaMethod.getJavaMethod();
        Map<String, String> pathParamsMap = new LinkedHashMap<>();
        Map<String, String> queryParamsMap = new LinkedHashMap<>();

        apiMethodDoc.getPathParams().stream().filter(Objects::nonNull).filter(p -> StringUtil.isNotEmpty(p.getValue()) || p.isConfigParam())
                .forEach(param -> pathParamsMap.put(param.getSourceField(), param.getValue()));
        apiMethodDoc.getQueryParams().stream().filter(Objects::nonNull).filter(p -> StringUtil.isNotEmpty(p.getValue()) || p.isConfigParam())
                .forEach(param -> queryParamsMap.put(param.getSourceField(), param.getValue()));
        List<JavaAnnotation> methodAnnotations = method.getAnnotations();
        Map<String, MappingAnnotation> mappingAnnotationMap = frameworkAnnotations.getMappingAnnotations();
        for (JavaAnnotation annotation : methodAnnotations) {
            String annotationName = annotation.getType().getName();
            MappingAnnotation mappingAnnotation = mappingAnnotationMap.get(annotationName);
            if (Objects.nonNull(mappingAnnotation) && StringUtil.isNotEmpty(mappingAnnotation.getParamsProp())) {
                Object paramsObjects = annotation.getNamedParameter(mappingAnnotation.getParamsProp());
                if (Objects.isNull(paramsObjects)) {
                    continue;
                }
                String params = StringUtil.removeQuotes(paramsObjects.toString());
                if (!params.startsWith("[")) {
                    mappingParamProcess(paramsObjects.toString(), queryParamsMap);
                    continue;
                }
                List<String> headers = (LinkedList) paramsObjects;
                for (String str : headers) {
                    mappingParamProcess(str, queryParamsMap);
                }
            }
        }
        List<JavaParameter> parameterList = method.getParameters();
        List<ApiReqParam> reqHeaderList = apiMethodDoc.getRequestHeaders();
        if (parameterList.size() < 1) {
            String path = apiMethodDoc.getPath().split(";")[0];
            path = DocUtil.formatAndRemove(path, pathParamsMap);
            String url = UrlUtil.urlJoin(path, queryParamsMap);
            url = StringUtil.removeQuotes(url);
            url = apiMethodDoc.getServerUrl() + "/" + url;
            url = UrlUtil.simplifyUrl(url);
            CurlRequest curlRequest = CurlRequest.builder()
                    .setContentType(apiMethodDoc.getContentType())
                    .setType(methodType)
                    .setReqHeaders(reqHeaderList)
                    .setUrl(url);
            String format = CurlUtil.toCurl(curlRequest);
            return ApiRequestExample.builder().setUrl(apiMethodDoc.getUrl()).setExampleBody(format);
        }
        Set<String> ignoreSets = ignoreParamsSets(method);
        Map<String, JavaType> actualTypesMap = javaMethod.getActualTypesMap();
        boolean requestFieldToUnderline = configBuilder.getApiConfig().isRequestFieldToUnderline();
        Map<String, String> replacementMap = configBuilder.getReplaceClassMap();
        Map<String, String> paramsComments = DocUtil.getCommentsByTag(method, DocTags.PARAM, null);
        List<String> mvcRequestAnnotations = this.listMvcRequestAnnotations();
        List<FormData> formDataList = new ArrayList<>();
        ApiRequestExample requestExample = ApiRequestExample.builder();
        out:
        for (JavaParameter parameter : parameterList) {
            JavaType javaType = parameter.getType();
            if (Objects.nonNull(actualTypesMap) && Objects.nonNull(actualTypesMap.get(javaType.getCanonicalName()))) {
                javaType = actualTypesMap.get(javaType.getCanonicalName());
            }
            String paramName = parameter.getName();
            if (ignoreSets.contains(paramName)) {
                continue;
            }
            String typeName = javaType.getFullyQualifiedName();
            String gicTypeName = javaType.getGenericCanonicalName();

            String commentClass = paramsComments.get(paramName);
            //ignore request params
            if (Objects.nonNull(commentClass) && commentClass.contains(IGNORE)) {
                continue;
            }
            String rewriteClassName = this.getRewriteClassName(replacementMap, typeName, commentClass);
            // rewrite class
            if (JavaClassValidateUtil.isClassName(rewriteClassName)) {
                gicTypeName = rewriteClassName;
                typeName = DocClassUtil.getSimpleName(rewriteClassName);
            }
            if (JavaClassValidateUtil.isMvcIgnoreParams(typeName, configBuilder.getApiConfig().getIgnoreRequestParams())) {
                continue;
            }
            String simpleTypeName = javaType.getValue();
            typeName = DocClassUtil.rewriteRequestParam(typeName);
            gicTypeName = DocClassUtil.rewriteRequestParam(gicTypeName);

            JavaClass javaClass = configBuilder.getJavaProjectBuilder().getClassByName(typeName);
            String[] globGicName = DocClassUtil.getSimpleGicName(gicTypeName);
            String comment = this.paramCommentResolve(paramsComments.get(paramName));
            String mockValue = JavaFieldUtil.createMockValue(paramsComments, paramName, gicTypeName, simpleTypeName);
            if (queryParamsMap.containsKey(paramName)) {
                mockValue = queryParamsMap.get(paramName);
            }
            if (requestFieldToUnderline) {
                paramName = StringUtil.camelToUnderline(paramName);
            }
            List<JavaAnnotation> annotations = parameter.getAnnotations();
            Set<String> groupClasses = JavaClassUtil.getParamGroupJavaClass(annotations, configBuilder.getJavaProjectBuilder());
            boolean paramAdded = false;
            boolean requestParam = false;
            for (JavaAnnotation annotation : annotations) {
                String annotationName = annotation.getType().getValue();
                String fullName = annotation.getType().getSimpleName();
                if (!mvcRequestAnnotations.contains(fullName) || paramAdded) {
                    continue;
                }
                if (ignoreMvcParamWithAnnotation(annotationName)) {
                    continue out;
                }

                AnnotationValue annotationDefaultVal = annotation.getProperty(DocAnnotationConstants.DEFAULT_VALUE_PROP);

                if (Objects.nonNull(annotationDefaultVal)) {
                    mockValue = DocUtil.resolveAnnotationValue(annotationDefaultVal);
                }
                paramName = getParamName(paramName, annotation);
                if (frameworkAnnotations.getRequestBodyAnnotation().getAnnotationName().equals(annotationName)) {
                    apiMethodDoc.setContentType(JSON_CONTENT_TYPE);
                    if (Objects.nonNull(configBuilder.getApiConfig().getRequestBodyAdvice())
                            && Objects.isNull(method.getTagByName(IGNORE_REQUEST_BODY_ADVICE))) {
                        String requestBodyAdvice = configBuilder.getApiConfig().getRequestBodyAdvice().getClassName();
                        typeName = configBuilder.getApiConfig().getRequestBodyAdvice().getClassName();
                        gicTypeName = requestBodyAdvice + "<" + gicTypeName + ">";
                    }

                    boolean isArrayOrCollection = false;
                    if (JavaClassValidateUtil.isArray(typeName) || JavaClassValidateUtil.isCollection(typeName)) {
                        simpleTypeName = globGicName[0];
                        isArrayOrCollection = true;
                    }

                    if (JavaClassValidateUtil.isPrimitive(simpleTypeName)) {
                        if (isArrayOrCollection) {
                            if (StringUtil.isNotEmpty(mockValue)) {
                                mockValue = "[" + mockValue + "]";
                            } else {
                                mockValue = "[" + DocUtil.getValByTypeAndFieldName(simpleTypeName, paramName) + "]";
                            }
                            mockValue = JsonUtil.toPrettyFormat(mockValue);
                        }
                        requestExample.setJsonBody(mockValue).setJson(true);
                    } else {
                        String json = JsonBuildHelper.buildJson(typeName, gicTypeName, Boolean.FALSE, 0, new HashMap<>(), groupClasses, configBuilder);
                        requestExample.setJsonBody(JsonUtil.toPrettyFormat(json)).setJson(true);
                    }
                    queryParamsMap.remove(paramName);
                    paramAdded = true;
                } else if (frameworkAnnotations.getPathVariableAnnotation().getAnnotationName().contains(annotationName)) {
                    if (javaClass.isEnum()) {
                        Object value = JavaClassUtil.getEnumValue(javaClass, Boolean.TRUE);
                        mockValue = StringUtil.removeQuotes(String.valueOf(value));
                    }
                    if (pathParamsMap.containsKey(paramName)) {
                        mockValue = pathParamsMap.get(paramName);
                    }
                    pathParamsMap.put(paramName, mockValue);
                    paramAdded = true;
                } else if (frameworkAnnotations.getRequestParamAnnotation().getAnnotationName().contains(annotationName)) {
                    if (javaClass.isEnum()) {
                        Object value = JavaClassUtil.getEnumValue(javaClass, Boolean.TRUE);
                        mockValue = StringUtil.removeQuotes(String.valueOf(value));
                    }
                    if (queryParamsMap.containsKey(paramName)) {
                        mockValue = queryParamsMap.get(paramName);
                    }
                    if (JavaClassValidateUtil.isPrimitive(simpleTypeName)) {
                        requestExample.addJsonBody(mockValue);
                    }
                    if (JavaClassValidateUtil.isFile(typeName)) {
                        break;
                    }
                    // array and list
                    queryParamsMap.put(paramName, mockValue);
                    requestParam = true;
                    paramAdded = true;
                }
            }
            if (paramAdded) {
                continue;
            }
            //file upload
            if (JavaClassValidateUtil.isFile(gicTypeName)) {
                apiMethodDoc.setContentType(FILE_CONTENT_TYPE);
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setType("file");
                if (typeName.contains("[]") || typeName.endsWith(">")) {
                    comment = comment + "(array of file)";
                    formData.setType(DocGlobalConstants.PARAM_TYPE_FILE);
                    formData.setHasItems(true);
                }
                formData.setDescription(comment);
                formData.setValue(mockValue);
                formDataList.add(formData);
            } else if (JavaClassValidateUtil.isPrimitive(typeName) && !requestParam) {
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setDescription(comment);
                formData.setType("text");
                formData.setValue(mockValue);
                formDataList.add(formData);
            } else if (JavaClassValidateUtil.isArray(typeName) || JavaClassValidateUtil.isCollection(typeName)) {
                String gicName = globGicName[0];
                if (JavaClassValidateUtil.isArray(gicName)) {
                    gicName = gicName.substring(0, gicName.indexOf("["));
                }
                if (!JavaClassValidateUtil.isPrimitive(gicName)
                        && !configBuilder.getJavaProjectBuilder().getClassByName(gicName).isEnum()) {
                    throw new RuntimeException("can't support binding Collection on method "
                            + method.getName() + "Check it in " + method.getDeclaringClass().getCanonicalName());
                }
                String value = null;
                JavaClass javaClass1 = configBuilder.getClassByName(gicName);
                if (Objects.nonNull(javaClass1) && javaClass1.isEnum()) {
                    value = String.valueOf(JavaClassUtil.getEnumValue(javaClass1, Boolean.TRUE));
                } else {
                    value = RandomUtil.randomValueByType(gicName);
                }
                FormData formData = new FormData();
                formData.setKey(paramName);
                if (!paramName.contains("[]")) {
                    formData.setKey(paramName + "[]");
                }
                formData.setDescription(comment);
                formData.setType("text");
                formData.setValue(value);
                formDataList.add(formData);
            } else if (javaClass.isEnum()) {
                // do nothing
                Object value = JavaClassUtil.getEnumValue(javaClass, Boolean.TRUE);
                String strVal = StringUtil.removeQuotes(String.valueOf(value));
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setType("text");
                formData.setDescription(comment);
                formData.setValue(strVal);
                formDataList.add(formData);
            } else {
                formDataList.addAll(FormDataBuildHelper.getFormData(gicTypeName, new HashMap<>(), 0, configBuilder, DocGlobalConstants.EMPTY));
            }
        }

        // set content-type to fromData
        boolean hasFormDataUploadFile = formDataList.stream().anyMatch(form -> Objects.equals(form.getType(), DocGlobalConstants.PARAM_TYPE_FILE));
        Map<Boolean, List<FormData>> formDataGroupMap = formDataList.stream().collect(Collectors.groupingBy(e -> Objects.equals(e.getType(), DocGlobalConstants.PARAM_TYPE_FILE)));
        List<FormData> fileFormDataList = formDataGroupMap.getOrDefault(Boolean.TRUE, new ArrayList<>());
        if (hasFormDataUploadFile) {
            formDataList = formDataGroupMap.getOrDefault(Boolean.FALSE, new ArrayList<>());
            apiMethodDoc.setContentType(FILE_CONTENT_TYPE);
        }

        requestExample.setFormDataList(formDataList);
        String[] paths = apiMethodDoc.getPath().split(";");
        String path = paths[0];
        String body;
        String exampleBody;
        String url;
        //curl send file to convert
        final Map<String, String> formDataToMap = DocUtil.formDataToMap(formDataList);
        // formData add to params '--data'
        queryParamsMap.putAll(formDataToMap);
        if (Methods.POST.getValue().equals(methodType) || Methods.PUT.getValue().equals(methodType)) {
            //for post put
            path = DocUtil.formatAndRemove(path, pathParamsMap);
            body = UrlUtil.urlJoin(DocGlobalConstants.EMPTY, queryParamsMap)
                    .replace("?", DocGlobalConstants.EMPTY);
            body = StringUtil.removeQuotes(body);
            url = apiMethodDoc.getServerUrl() + "/" + path;
            url = UrlUtil.simplifyUrl(url);

            if (requestExample.isJson()) {
                if (StringUtil.isNotEmpty(body)) {
                    url = url + "?" + body;
                }
                CurlRequest curlRequest = CurlRequest.builder()
                        .setBody(requestExample.getJsonBody())
                        .setContentType(apiMethodDoc.getContentType())
                        .setType(methodType)
                        .setReqHeaders(reqHeaderList)
                        .setUrl(url);
                exampleBody = CurlUtil.toCurl(curlRequest);
            } else {
                CurlRequest curlRequest;
                if (StringUtil.isNotEmpty(body)) {
                    curlRequest = CurlRequest.builder()
                            .setBody(body)
                            .setContentType(apiMethodDoc.getContentType())
                            .setFileFormDataList(fileFormDataList)
                            .setType(methodType)
                            .setReqHeaders(reqHeaderList)
                            .setUrl(url);
                } else {
                    curlRequest = CurlRequest.builder()
                            .setBody(requestExample.getJsonBody())
                            .setContentType(apiMethodDoc.getContentType())
                            .setFileFormDataList(fileFormDataList)
                            .setType(methodType)
                            .setReqHeaders(reqHeaderList)
                            .setUrl(url);
                }
                exampleBody = CurlUtil.toCurl(curlRequest);
            }
            requestExample.setExampleBody(exampleBody).setUrl(url);
        } else {
            // for get delete
            path = DocUtil.formatAndRemove(path, pathParamsMap);
            url = UrlUtil.urlJoin(path, queryParamsMap);
            url = StringUtil.removeQuotes(url);
            url = apiMethodDoc.getServerUrl() + "/" + url;
            url = UrlUtil.simplifyUrl(url);
            CurlRequest curlRequest = CurlRequest.builder()
                    .setBody(requestExample.getJsonBody())
                    .setContentType(apiMethodDoc.getContentType())
                    .setType(methodType)
                    .setReqHeaders(reqHeaderList)
                    .setUrl(url);
            exampleBody = CurlUtil.toCurl(curlRequest);

            requestExample.setExampleBody(exampleBody)
                    .setJsonBody(requestExample.isJson() ? requestExample.getJsonBody() : DocGlobalConstants.EMPTY)
                    .setUrl(url);
        }
        return requestExample;
    }


    default ApiMethodReqParam requestParams(final DocJavaMethod docJavaMethod, ProjectDocConfigBuilder builder,
                                            List<ApiReqParam> configApiReqParams, FrameworkAnnotations frameworkAnnotations) {
        JavaMethod javaMethod = docJavaMethod.getJavaMethod();
        boolean isStrict = builder.getApiConfig().isStrict();
        String className = javaMethod.getDeclaringClass().getCanonicalName();
        Map<String, String> replacementMap = builder.getReplaceClassMap();
        Map<String, String> paramTagMap = DocUtil.getCommentsByTag(javaMethod, DocTags.PARAM, className);
        Map<String, String> paramsComments = DocUtil.getCommentsByTag(javaMethod, DocTags.PARAM, null);
        List<ApiParam> paramList = new ArrayList<>();
        Map<String, String> mappingParams = new HashMap<>();
        List<JavaAnnotation> methodAnnotations = javaMethod.getAnnotations();
        Map<String, MappingAnnotation> mappingAnnotationMap = frameworkAnnotations.getMappingAnnotations();
        for (JavaAnnotation annotation : methodAnnotations) {
            String annotationName = annotation.getType().getName();
            MappingAnnotation mappingAnnotation = mappingAnnotationMap.get(annotationName);
            if (Objects.nonNull(mappingAnnotation) && StringUtil.isNotEmpty(mappingAnnotation.getParamsProp())) {
                Object paramsObjects = annotation.getNamedParameter("params");
                if (Objects.isNull(paramsObjects)) {
                    continue;
                }
                String params = StringUtil.removeQuotes(paramsObjects.toString());
                if (!params.startsWith("[")) {
                    mappingParamToApiParam(paramsObjects.toString(), paramList, mappingParams);
                    continue;
                }
                List<String> headers = (LinkedList) paramsObjects;
                for (String str : headers) {
                    mappingParamToApiParam(str, paramList, mappingParams);
                }
            }
        }
        final Map<String, Map<String, ApiReqParam>> collect = configApiReqParams.stream().collect(Collectors.groupingBy(ApiReqParam::getParamIn,
                Collectors.toMap(ApiReqParam::getName, m -> m, (k1, k2) -> k1)));
        final Map<String, ApiReqParam> pathReqParamMap = collect.getOrDefault(ApiReqParamInTypeEnum.PATH.getValue(), Collections.emptyMap());
        final Map<String, ApiReqParam> queryReqParamMap = collect.getOrDefault(ApiReqParamInTypeEnum.QUERY.getValue(), Collections.emptyMap());
        List<JavaParameter> parameterList = javaMethod.getParameters();
        if (parameterList.isEmpty()) {
            AtomicInteger querySize = new AtomicInteger(paramList.size() + 1);
            paramList.addAll(queryReqParamMap.values().stream()
                    .map(p -> ApiReqParam.convertToApiParam(p).setQueryParam(true).setId(querySize.getAndIncrement()))
                    .collect(Collectors.toList()));
            AtomicInteger pathSize = new AtomicInteger(1);
            return ApiMethodReqParam.builder()
                    .setPathParams(new ArrayList<>(pathReqParamMap.values().stream()
                            .map(p -> ApiReqParam.convertToApiParam(p).setPathParam(true).setId(pathSize.getAndIncrement()))
                            .collect(Collectors.toList())))
                    .setQueryParams(paramList)
                    .setRequestParams(new ArrayList<>(0));
        }
        boolean requestFieldToUnderline = builder.getApiConfig().isRequestFieldToUnderline();
        Set<String> ignoreSets = ignoreParamsSets(javaMethod);
        Map<String, JavaType> actualTypesMap = docJavaMethod.getActualTypesMap();
        int requestBodyCounter = 0;
        out:
        for (JavaParameter parameter : parameterList) {
            String paramName = parameter.getName();
            if (ignoreSets.contains(paramName) || mappingParams.containsKey(paramName)) {
                continue;
            }

            JavaType javaType = parameter.getType();
            if (Objects.nonNull(actualTypesMap) && Objects.nonNull(actualTypesMap.get(javaType.getCanonicalName()))) {
                javaType = actualTypesMap.get(javaType.getCanonicalName());
            }
            String typeName = javaType.getGenericCanonicalName();
            String simpleTypeName = javaType.getValue();
            String simpleName = javaType.getValue().toLowerCase();
            String fullTypeName = javaType.getFullyQualifiedName();
            String commentClass = paramTagMap.get(paramName);
            String rewriteClassName = getRewriteClassName(replacementMap, fullTypeName, commentClass);
            // rewrite class
            if (JavaClassValidateUtil.isClassName(rewriteClassName)) {
                typeName = rewriteClassName;
                fullTypeName = DocClassUtil.getSimpleName(rewriteClassName);
            }
            if (JavaClassValidateUtil.isMvcIgnoreParams(typeName, builder.getApiConfig().getIgnoreRequestParams())) {
                continue;
            }
            fullTypeName = DocClassUtil.rewriteRequestParam(fullTypeName);
            typeName = DocClassUtil.rewriteRequestParam(typeName);
            if (!paramTagMap.containsKey(paramName) && JavaClassValidateUtil.isPrimitive(fullTypeName) && isStrict) {
                throw new RuntimeException("ERROR: Unable to find javadoc @param for actual param \""
                        + paramName + "\" in method " + javaMethod.getName() + " from " + className);
            }
            StringBuilder comment = new StringBuilder(this.paramCommentResolve(paramTagMap.get(paramName)));
            if (requestFieldToUnderline) {
                paramName = StringUtil.camelToUnderline(paramName);
            }
            String mockValue = JavaFieldUtil.createMockValue(paramsComments, paramName, typeName, simpleTypeName);
            JavaClass javaClass = builder.getJavaProjectBuilder().getClassByName(fullTypeName);
            List<JavaAnnotation> annotations = parameter.getAnnotations();
            Set<String> groupClasses = JavaClassUtil.getParamGroupJavaClass(annotations, builder.getJavaProjectBuilder());
            String strRequired = "false";
            boolean isPathVariable = false;
            boolean isRequestBody = false;
            boolean required = false;
            for (JavaAnnotation annotation : annotations) {
                String annotationName = annotation.getType().getValue();
                if (ignoreMvcParamWithAnnotation(annotationName)) {
                    continue out;
                }

                if (frameworkAnnotations.getRequestParamAnnotation().getAnnotationName().equals(annotationName) ||
                        frameworkAnnotations.getPathVariableAnnotation().getAnnotationName().equals(annotationName)) {
                    String defaultValueProp = DocAnnotationConstants.DEFAULT_VALUE_PROP;
                    String requiredProp = DocAnnotationConstants.REQUIRED_PROP;
                    if (frameworkAnnotations.getRequestParamAnnotation().getAnnotationName().equals(annotationName)) {
                        defaultValueProp = frameworkAnnotations.getRequestParamAnnotation().getDefaultValueProp();
                        requiredProp = frameworkAnnotations.getRequestParamAnnotation().getRequiredProp();
                    }
                    if (frameworkAnnotations.getPathVariableAnnotation().getAnnotationName().equals(annotationName)) {
                        defaultValueProp = frameworkAnnotations.getPathVariableAnnotation().getDefaultValueProp();
                        requiredProp = frameworkAnnotations.getPathVariableAnnotation().getRequiredProp();
                        isPathVariable = true;
                    }
                    AnnotationValue annotationDefaultVal = annotation.getProperty(defaultValueProp);
                    if (Objects.nonNull(annotationDefaultVal)) {
                        mockValue = DocUtil.resolveAnnotationValue(annotationDefaultVal);
                    }
                    paramName = getParamName(paramName, annotation);
                    AnnotationValue annotationRequired = annotation.getProperty(requiredProp);
                    if (Objects.nonNull(annotationRequired)) {
                        strRequired = annotationRequired.toString();
                    } else {
                        strRequired = "true";
                    }
                }
                if (JavaClassValidateUtil.isJSR303Required(annotationName)) {
                    strRequired = "true";
                }
                if (frameworkAnnotations.getRequestBodyAnnotation().getAnnotationName().equals(annotationName)) {
                    if (requestBodyCounter > 0) {
                        throw new RuntimeException("You have use @RequestBody Passing multiple variables  for method "
                                + javaMethod.getName() + " in " + className + ",@RequestBody annotation could only bind one variables.");
                    }
                    if (Objects.nonNull(builder.getApiConfig().getRequestBodyAdvice())
                            && Objects.isNull(javaMethod.getTagByName(IGNORE_REQUEST_BODY_ADVICE))) {
                        String requestBodyAdvice = builder.getApiConfig().getRequestBodyAdvice().getClassName();
                        fullTypeName = requestBodyAdvice;
                        typeName = requestBodyAdvice + "<" + typeName + ">";

                    }
                    mockValue = JsonBuildHelper.buildJson(fullTypeName, typeName, Boolean.FALSE, 0, new HashMap<>(), groupClasses, builder);
                    requestBodyCounter++;
                    isRequestBody = true;
                }
                required = Boolean.parseBoolean(strRequired);
            }
            comment.append(JavaFieldUtil.getJsrComment(annotations));
            //file upload
            if (JavaClassValidateUtil.isFile(typeName)) {
                ApiParam param = ApiParam.of().setField(paramName).setType(DocGlobalConstants.PARAM_TYPE_FILE)
                        .setId(paramList.size() + 1).setQueryParam(true)
                        .setRequired(required).setVersion(DocGlobalConstants.DEFAULT_VERSION)
                        .setDesc(comment.toString());
                if (typeName.contains("[]") || typeName.endsWith(">")) {
                    comment.append("(array of file)");
                    param.setType(DocGlobalConstants.PARAM_TYPE_FILE);
                    param.setDesc(comment.toString());
                    param.setHasItems(true);
                }
                paramList.add(param);
                continue;
            }

            boolean queryParam = !isRequestBody && !isPathVariable;
            if (JavaClassValidateUtil.isCollection(fullTypeName) || JavaClassValidateUtil.isArray(fullTypeName)) {
                if (JavaClassValidateUtil.isCollection(typeName)) {
                    typeName = typeName + "<T>";
                }
                String[] gicNameArr = DocClassUtil.getSimpleGicName(typeName);
                String gicName = gicNameArr[0];
                if (JavaClassValidateUtil.isArray(gicName)) {
                    gicName = gicName.substring(0, gicName.indexOf("["));
                }
                // handle array and list mock value
                mockValue = JavaFieldUtil.createMockValue(paramsComments, paramName, gicName, gicName);
                if (StringUtil.isNotEmpty(mockValue) && !mockValue.contains(",")) {
                    mockValue = StringUtils.join(mockValue, ",", JavaFieldUtil.createMockValue(paramsComments, paramName, gicName, gicName));
                }
                JavaClass gicJavaClass = builder.getJavaProjectBuilder().getClassByName(gicName);
                if (gicJavaClass.isEnum()) {
                    Object value = JavaClassUtil.getEnumValue(gicJavaClass, Boolean.TRUE);
                    ApiParam param = ApiParam.of().setField(paramName).setDesc(comment + ",[array of enum]")
                            .setRequired(required)
                            .setPathParam(isPathVariable)
                            .setQueryParam(queryParam)
                            .setId(paramList.size() + 1)
                            .setEnumValues(JavaClassUtil.getEnumValues(gicJavaClass))
                            .setEnumInfo(JavaClassUtil.getEnumInfo(gicJavaClass, builder))
                            .setType("array").setValue(String.valueOf(value));
                    paramList.add(param);
                    if (requestBodyCounter > 0) {
                        Map<String, Object> map = OpenApiSchemaUtil.arrayTypeSchema(gicName);
                        docJavaMethod.setRequestSchema(map);
                    }
                } else if (JavaClassValidateUtil.isPrimitive(gicName)) {
                    String shortSimple = DocClassUtil.processTypeNameForParams(gicName);
                    ApiParam param = ApiParam.of()
                            .setField(paramName)
                            .setDesc(comment + ",[array of " + shortSimple + "]")
                            .setRequired(required)
                            .setPathParam(isPathVariable)
                            .setQueryParam(queryParam)
                            .setId(paramList.size() + 1)
                            .setType("array")
                            .setValue(mockValue);
                    paramList.add(param);
                    if (requestBodyCounter > 0) {
                        Map<String, Object> map = OpenApiSchemaUtil.arrayTypeSchema(gicName);
                        docJavaMethod.setRequestSchema(map);
                    }
                } else if (JavaClassValidateUtil.isFile(gicName)) {
                    //file upload
                    ApiParam param = ApiParam.of().setField(paramName)
                            .setType(DocGlobalConstants.PARAM_TYPE_FILE)
                            .setId(paramList.size() + 1).setQueryParam(true)
                            .setRequired(required).setVersion(DocGlobalConstants.DEFAULT_VERSION)
                            .setHasItems(true)
                            .setDesc(comment + "(array of file)");
                    paramList.add(param);
                } else {
                    if (requestBodyCounter > 0) {
                        //for json
                        paramList.addAll(ParamsBuildHelper.buildParams(gicNameArr[0], DocGlobalConstants.EMPTY, 0,
                                String.valueOf(required), Boolean.FALSE, new HashMap<>(), builder,
                                groupClasses, 0, Boolean.TRUE, null));
                    }
                }
            } else if (JavaClassValidateUtil.isPrimitive(fullTypeName)) {
                ApiParam param = ApiParam.of()
                        .setField(paramName)
                        .setType(DocClassUtil.processTypeNameForParams(simpleName))
                        .setId(paramList.size() + 1)
                        .setPathParam(isPathVariable)
                        .setQueryParam(queryParam)
                        .setValue(mockValue)
                        .setDesc(comment.toString())
                        .setRequired(required)
                        .setVersion(DocGlobalConstants.DEFAULT_VERSION);
                paramList.add(param);
                if (requestBodyCounter > 0) {
                    Map<String, Object> map = OpenApiSchemaUtil.primaryTypeSchema(simpleName);
                    docJavaMethod.setRequestSchema(map);
                }
            } else if (JavaClassValidateUtil.isMap(fullTypeName)) {
                log.warning("When using smart-doc, it is not recommended to use Map to receive parameters, Check it in "
                        + javaMethod.getDeclaringClass().getCanonicalName() + "#" + javaMethod.getName());
                //is map without Gic
                if (JavaClassValidateUtil.isMap(typeName)) {
                    ApiParam apiParam = ApiParam.of()
                            .setField(paramName)
                            .setType("map")
                            .setId(paramList.size() + 1)
                            .setPathParam(isPathVariable)
                            .setQueryParam(queryParam)
                            .setDesc(comment.toString())
                            .setRequired(required)
                            .setVersion(DocGlobalConstants.DEFAULT_VERSION);
                    paramList.add(apiParam);
                    if (requestBodyCounter > 0) {
                        Map<String, Object> map = OpenApiSchemaUtil.mapTypeSchema("object");
                        docJavaMethod.setRequestSchema(map);
                    }
                    continue;
                }
                String[] gicNameArr = DocClassUtil.getSimpleGicName(typeName);
                if (JavaClassValidateUtil.isPrimitive(gicNameArr[1])) {
                    ApiParam apiParam = ApiParam.of()
                            .setField(paramName)
                            .setType("map")
                            .setId(paramList.size() + 1)
                            .setPathParam(isPathVariable)
                            .setQueryParam(queryParam)
                            .setDesc(comment.toString())
                            .setRequired(required)
                            .setVersion(DocGlobalConstants.DEFAULT_VERSION);
                    paramList.add(apiParam);
                    if (requestBodyCounter > 0) {
                        Map<String, Object> map = OpenApiSchemaUtil.mapTypeSchema(gicNameArr[1]);
                        docJavaMethod.setRequestSchema(map);
                    }
                } else {
                    paramList.addAll(ParamsBuildHelper.buildParams(gicNameArr[1], DocGlobalConstants.EMPTY, 0,
                            String.valueOf(required), Boolean.FALSE, new HashMap<>(),
                            builder, groupClasses, 0, Boolean.FALSE, null));
                }

            }
            // param is enum
            else if (javaClass.isEnum()) {
                String o = JavaClassUtil.getEnumParams(javaClass);
                Object value = JavaClassUtil.getEnumValue(javaClass, isPathVariable || queryParam);
                ApiParam param = ApiParam.of().setField(paramName)
                        .setId(paramList.size() + 1)
                        .setPathParam(isPathVariable)
                        .setQueryParam(queryParam)
                        .setValue(String.valueOf(value))
                        .setType("enum").setDesc(StringUtil.removeQuotes(o))
                        .setRequired(required)
                        .setVersion(DocGlobalConstants.DEFAULT_VERSION)
                        .setEnumInfo(JavaClassUtil.getEnumInfo(javaClass, builder))
                        .setEnumValues(JavaClassUtil.getEnumValues(javaClass));
                paramList.add(param);
            } else {
                paramList.addAll(ParamsBuildHelper.buildParams(typeName, DocGlobalConstants.EMPTY, 0,
                        String.valueOf(required), Boolean.FALSE, new HashMap<>(), builder, groupClasses, 0, Boolean.FALSE, null));
            }
        }
        return ApiParamTreeUtil.buildMethodReqParam(paramList, queryReqParamMap, pathReqParamMap, requestBodyCounter);
    }

    default boolean defaultEntryPoint(JavaClass cls, FrameworkAnnotations frameworkAnnotations) {
        if (cls.isAnnotation() || cls.isEnum()) {
            return false;
        }
        if (Objects.isNull(frameworkAnnotations)) {
            return false;
        }
        List<JavaAnnotation> classAnnotations = DocClassUtil.getAnnotations(cls);
        Map<String, EntryAnnotation> entryAnnotationMap = frameworkAnnotations.getEntryAnnotations();
        for (JavaAnnotation annotation : classAnnotations) {
            String name = annotation.getType().getValue();
            if (entryAnnotationMap.containsKey(name)) {
                return true;
            }
            if (isEntryPoint(cls, frameworkAnnotations)) {
                return true;
            }
        }
        return false;
    }

    boolean isEntryPoint(JavaClass javaClass, FrameworkAnnotations frameworkAnnotations);

    List<String> listMvcRequestAnnotations();

    void requestMappingPostProcess(JavaClass javaClass, JavaMethod method, RequestMapping requestMapping);

    boolean ignoreMvcParamWithAnnotation(String annotation);
}