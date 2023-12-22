package st.coo.memo.service.resource;

import cn.hutool.core.io.file.FileNameUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import st.coo.memo.common.BizException;
import st.coo.memo.common.QiniuRegion;
import st.coo.memo.common.ResponseCode;
import st.coo.memo.common.SysConfigConstant;
import st.coo.memo.dto.resource.UploadResourceResponse;
import st.coo.memo.service.SysConfigService;

import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class QiNiuResourceProvider implements ResourceProvider {

    @Resource
    private SysConfigService sysConfigService;



    @Override
    public UploadResourceResponse upload(String filePath, String publicId) {
        String param = sysConfigService.getString(SysConfigConstant.QINIU_PARAM);
        Map<String, String> map = new Gson().fromJson(param, new TypeToken<Map<String, String>>() {
        }.getType());
        String accessKey = MapUtils.getString(map, "accessKey");
        String secretKey = MapUtils.getString(map, "secretKey");
        String bucket = MapUtils.getString(map, "bucket");
        String domain = MapUtils.getString(map, "domain");
        String prefix = MapUtils.getString(map, "prefix");
        String suffix = MapUtils.getString(map, "suffix");
        String region = MapUtils.getString(map, "region");

        if (StringUtils.isEmpty(accessKey) || StringUtils.isEmpty(secretKey) || StringUtils.isEmpty(bucket) || StringUtils.isEmpty(domain)) {
            throw new BizException(ResponseCode.fail, "七牛云相关参数没有设置");
        }

        Configuration cfg = new Configuration(Region.region0());
        if (Objects.equals(region, QiniuRegion.huadong.name())){
            cfg = new Configuration(Region.huadong());
        }else if (Objects.equals(region, QiniuRegion.huadongZheJiang2.name())){
            cfg = new Configuration(Region.huadongZheJiang2());
        }else if (Objects.equals(region, QiniuRegion.huabei.name())){
            cfg = new Configuration(Region.huabei());
        }else if (Objects.equals(region, QiniuRegion.huanan.name())){
            cfg = new Configuration(Region.huanan());
        }else if (Objects.equals(region, QiniuRegion.beimei.name())){
            cfg = new Configuration(Region.beimei());
        }else if (Objects.equals(region, QiniuRegion.xinjiapo.name())){
            cfg = new Configuration(Region.xinjiapo());
        }

        log.info("cfg:{}",cfg);
        cfg.resumableUploadAPIVersion = Configuration.ResumableUploadAPIVersion.V2;// 指定分片上传版本
        UploadManager uploadManager = new UploadManager(cfg);
        Auth auth = Auth.create(accessKey, secretKey);
        String upToken = auth.uploadToken(bucket);
        String key = publicId;
        if (StringUtils.isNotEmpty(prefix)) {
            key = prefix + "/" + publicId;
        }

        try {
            Response response = uploadManager.put(filePath, key, upToken);
            //解析上传成功的结果
            DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);
            log.info("上传到七牛云成功:{} => {}", putRet.key, putRet.hash);
        } catch (Exception ex) {
            log.error("上传到七牛云异常", ex);
            throw new BizException(ResponseCode.fail, "上传资源失败");
        }

        UploadResourceResponse uploadResourceResponse = new UploadResourceResponse();
        uploadResourceResponse.setUrl(domain+"/"+key);
        uploadResourceResponse.setSuffix(suffix);
        uploadResourceResponse.setPublicId(publicId);
        return uploadResourceResponse;
    }
}
