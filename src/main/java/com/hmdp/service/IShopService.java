package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询店铺信息
     * @param id 店铺id
     * @return 结果
     */
    Result queryById(Long id);

    /**
     * 更新店铺信息
     * @param shop
     * @return
     */
    Result update(Shop shop);


    void saveShop2Redis(Long id, Long expireSeconds);
}
