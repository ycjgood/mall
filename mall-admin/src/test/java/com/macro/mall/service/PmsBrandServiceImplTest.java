package com.macro.mall.service;

import com.macro.mall.dto.PmsBrandParam;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsBrand;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.service.impl.PmsBrandServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 品牌管理服务单元测试
 * <p>
 * 只测纯业务逻辑，不启动 Spring 容器、不连数据库，因此可以在 CI 里秒级跑完。
 * 这类测试的写法就是后续给其它 Service 补测试的模板。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PmsBrandService 品牌管理")
class PmsBrandServiceImplTest {

    @Mock
    private PmsBrandMapper brandMapper;

    @Mock
    private PmsProductMapper productMapper;

    @InjectMocks
    private PmsBrandServiceImpl brandService;

    @Test
    @DisplayName("新增品牌：首字母为空时自动取名称首字")
    void createBrand_shouldGenerateFirstLetterFromName() {
        PmsBrandParam param = new PmsBrandParam();
        param.setName("华为");
        param.setFirstLetter(null);
        when(brandMapper.insertSelective(any(PmsBrand.class))).thenReturn(1);

        int result = brandService.createBrand(param);

        ArgumentCaptor<PmsBrand> captor = ArgumentCaptor.forClass(PmsBrand.class);
        verify(brandMapper, times(1)).insertSelective(captor.capture());
        assertThat(captor.getValue().getFirstLetter()).isEqualTo("华");
        assertThat(captor.getValue().getName()).isEqualTo("华为");
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("新增品牌：已指定首字母时不被覆盖")
    void createBrand_shouldKeepProvidedFirstLetter() {
        PmsBrandParam param = new PmsBrandParam();
        param.setName("Apple");
        param.setFirstLetter("A");
        when(brandMapper.insertSelective(any(PmsBrand.class))).thenReturn(1);

        brandService.createBrand(param);

        ArgumentCaptor<PmsBrand> captor = ArgumentCaptor.forClass(PmsBrand.class);
        verify(brandMapper).insertSelective(captor.capture());
        assertThat(captor.getValue().getFirstLetter()).isEqualTo("A");
    }

    @Test
    @DisplayName("更新品牌：同步刷新商品表中的品牌名称")
    void updateBrand_shouldSyncBrandNameToProducts() {
        PmsBrandParam param = new PmsBrandParam();
        param.setName("小米");
        param.setFirstLetter("X");
        when(brandMapper.updateByPrimaryKeySelective(any(PmsBrand.class))).thenReturn(1);

        int result = brandService.updateBrand(1L, param);

        ArgumentCaptor<PmsProduct> productCaptor = ArgumentCaptor.forClass(PmsProduct.class);
        verify(productMapper).updateByExampleSelective(productCaptor.capture(), any());
        assertThat(productCaptor.getValue().getBrandName()).isEqualTo("小米");

        ArgumentCaptor<PmsBrand> brandCaptor = ArgumentCaptor.forClass(PmsBrand.class);
        verify(brandMapper).updateByPrimaryKeySelective(brandCaptor.capture());
        assertThat(brandCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("删除品牌：不涉及商品表更新")
    void deleteBrand_shouldNotTouchProductTable() {
        when(brandMapper.deleteByPrimaryKey(9L)).thenReturn(1);

        assertThat(brandService.deleteBrand(9L)).isEqualTo(1);
        verify(productMapper, never()).updateByExampleSelective(any(), any());
    }
}
