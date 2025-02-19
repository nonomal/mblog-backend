package st.coo.memo.service.resource;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;
import st.coo.memo.common.*;
import st.coo.memo.dto.resource.UploadResourceResponse;
import st.coo.memo.entity.TMemo;
import st.coo.memo.entity.TResource;
import st.coo.memo.mapper.MemoMapperExt;
import st.coo.memo.mapper.ResourceMapperExt;
import st.coo.memo.service.SysConfigService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static st.coo.memo.entity.table.Tables.T_MEMO;
import static st.coo.memo.entity.table.Tables.T_RESOURCE;

@Slf4j
@Component
public class ResourceService implements ApplicationContextAware {

    @Resource
    private SysConfigService sysConfigService;

    private ApplicationContext applicationContext;

    @Value("${upload.storage.path}")
    private String tempPath;

    @Resource
    private ResourceMapperExt resourceMapper;

    @Resource
    private MemoMapperExt memoMapper;
    private final static Map<StorageType, Class<? extends ResourceProvider>> RESOURCE_PROVIDER_MAP = Maps.newHashMap();

    public ResourceService() {
        RESOURCE_PROVIDER_MAP.put(StorageType.LOCAL, LocalResourceProvider.class);
        RESOURCE_PROVIDER_MAP.put(StorageType.QINIU, QiNiuResourceProvider.class);
    }

    public List<UploadResourceResponse> upload(MultipartFile[] multipartFiles) {
        String value = sysConfigService.getString(SysConfigConstant.STORAGE_TYPE);
        StorageType storageType = StorageType.get(value);
        Class<? extends ResourceProvider> cls = RESOURCE_PROVIDER_MAP.get(storageType);
        ResourceProvider provider = applicationContext.getBean(cls);
        List<UploadResourceResponse> result = Lists.newArrayList();
        for (MultipartFile multipartFile : multipartFiles) {
            result.add(upload(multipartFile, storageType, provider));
        }
        return result;
    }

    private UploadResourceResponse upload(MultipartFile multipartFile, StorageType storageType, ResourceProvider provider) {
        String publicId = RandomStringUtils.randomAlphabetic(20);
        String originalFilename = multipartFile.getOriginalFilename();
        String fileName = publicId + "." + FileNameUtil.getSuffix(originalFilename);
        String parentDir = DateFormatUtils.format(new Date(), "yyyyMMdd");
        String targetPath = tempPath + File.separator + parentDir + File.separator + fileName;
        byte[] content = null;
        String fileType = "";
        String fileHash = "";
        try {
            FileUtil.mkParentDirs(targetPath);
            content = multipartFile.getBytes();
            fileHash = DigestUtils.md5DigestAsHex(content);
            TResource existResource = resourceMapper.selectOneByQuery(QueryWrapper.create().and(T_RESOURCE.FILE_HASH.eq(fileHash)));
            FileOutputStream target = new FileOutputStream(targetPath);
            if (existResource == null) {
                FileCopyUtils.copy(multipartFile.getInputStream(), target);
                fileType = Files.probeContentType(new File(targetPath).toPath());
            } else {
                targetPath = existResource.getInternalPath();
                fileType = existResource.getFileType();
            }

        } catch (Exception e) {
            log.error("upload resource error", e);
            throw new BizException(ResponseCode.fail, "上传文件异常:" + e.getLocalizedMessage());
        }
        String url = provider.upload(targetPath);

        TResource tResource = new TResource();
        tResource.setPublicId(publicId);
        tResource.setFileType(fileType);
        tResource.setFileName(originalFilename);
        tResource.setFileHash(DigestUtils.md5DigestAsHex(content));
        tResource.setSize(multipartFile.getSize());
        tResource.setMemoId(0);
        tResource.setInternalPath(targetPath);
        tResource.setExternalLink(url);
        tResource.setStorageType(storageType.name());
        tResource.setUserId(StpUtil.getLoginIdAsInt());
        resourceMapper.insertSelective(tResource);

        UploadResourceResponse uploadResourceResponse = new UploadResourceResponse();
        uploadResourceResponse.setPublicId(publicId);
        if (Objects.equals(tResource.getStorageType(),StorageType.LOCAL.name())){
            String domain = sysConfigService.getString(SysConfigConstant.DOMAIN);
            uploadResourceResponse.setUrl(domain+url);
        }

        return uploadResourceResponse;
    }

    public void get(String publicId, HttpServletResponse httpServletResponse) {
        TResource tResource = resourceMapper.selectOneById(publicId);
        if (tResource == null) {
            throw new BizException(ResponseCode.fail, "resource不存在");
        }

        boolean isLogin = StpUtil.isLogin();
        if (tResource.getMemoId() > 0) {
            QueryWrapper wrapper = QueryWrapper.create().and(T_MEMO.ID.eq(tResource.getMemoId()));
            if (isLogin) {
                wrapper.and(T_MEMO.VISIBILITY.in(Lists.newArrayList(Visibility.PUBLIC.name(), Visibility.PROTECT.name()))
                        .or(T_MEMO.VISIBILITY.eq(Visibility.PRIVATE).and(T_MEMO.USER_ID.eq(StpUtil.getLoginIdAsInt()))))
                ;
            } else {
                wrapper.and(T_MEMO.VISIBILITY.eq(Visibility.PUBLIC.name()));
            }
            TMemo memo = memoMapper.selectOneByQuery(wrapper);
            if (memo == null) {
                throw new BizException(ResponseCode.fail, "memo不存在");
            }
        }


        if (Objects.equals(tResource.getStorageType(), StorageType.LOCAL.name())) {
            File file = new File(tResource.getInternalPath());
            httpServletResponse.setContentType(tResource.getFileType());
            try {
                FileCopyUtils.copy(new FileInputStream(file), httpServletResponse.getOutputStream());
            } catch (IOException e) {
                log.error("get resource {} error", publicId, e);
                throw new BizException(ResponseCode.fail, "获取resource异常");
            }
            return;
        }
        httpServletResponse.setStatus(302);
        httpServletResponse.setHeader("Location", tResource.getExternalLink());
    }


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
