/**
 * All rights Reserved, Designed By Letv
 *
 * @Title: AboutRandom.java
 * @Package com.java.basic
 * @Description: TODO(用一句话描述该文件做什么)
 * @author: lenovo
 * @date: 2016年7月15日 上午11:32:43
 * @version
 */
package com.java.basic;

import java.util.Random;

/**
 * @author lenovo
 * @create time:2016年7月15日上午11:32:43
 * @Description:TODO(这里用一句话描述这个类的作用)
 */
public class AboutRandom {
    public static void main(String[] args) {
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            System.out.println(random.nextInt(1000000));
        }
    }

}
