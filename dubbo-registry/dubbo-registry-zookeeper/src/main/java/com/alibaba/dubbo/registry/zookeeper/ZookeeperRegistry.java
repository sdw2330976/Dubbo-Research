/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.registry.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.I0Itec.zkclient.exception.ZkNoNodeException;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * ZookeeperRegistry
 * 
 * @author william.liangf
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;
    
    private final static String DEFAULT_ROOT = "dubbo";

    private final String        root;
    
    private final Set<String> anyServices = new ConcurrentHashSet<String>();

    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<URL, ConcurrentMap<NotifyListener, ChildListener>>();
    
    private final ZookeeperClient zkClient;
    
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        //若provider的url是"0.0.0.0"或者在参数中带anyHost=true则抛出异常注册地址不存在
        if (url.isAnyHost()) {
    		throw new IllegalStateException("registry address == null");
    	}
        //服务分组  默认为"dubbo"
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (! group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        this.root = group;
        //创建Zookeeper客户端
        zkClient = zookeeperTransporter.connect(url);
        //添加动态监听器
        zkClient.addStateListener(new StateListener() {
            public void stateChanged(int state) {
            	if (state == RECONNECTED) {
	            	try {
						recover();
					} catch (Exception e) {
						logger.error(e.getMessage(), e);
					}
            	}
            }
        });
    }

    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doRegister(URL url) {
        try {
        	zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            //若provider的service的接口配置是"*"
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                //获取服务分组根路径
                String root = toRootPath();
                //获取服务的NotifyListener
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                if (listeners == null) {
                    //如果没有则创建一个
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                    listeners = zkListeners.get(url);
                }
                ChildListener zkListener = listeners.get(listener);
                //若没有子监听器则创建一个
                if (zkListener == null) {
                    listeners.putIfAbsent(listener, new ChildListener() {
                        public void childChanged(String parentPath, List<String> currentChilds) {
                            for (String child : currentChilds) {
                                if (! anyServices.contains(child)) {
                                    anyServices.add(child);
                                    subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child, 
                                            Constants.CHECK_KEY, String.valueOf(false)), listener);
                                }
                            }
                        }
                    });
                    zkListener = listeners.get(listener);
                }
                //向服务器订阅服务,注册中心会调用NotifyListener的notify函数返回服务列表
                zkClient.create(root, false);
                //获取服务地址列表
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (services != null && services.size() > 0) {//若存在服务
                    anyServices.addAll(services);
                    for (String service : services) {
                        //若serviceInterface是"*" 则从分组根路径遍历service并订阅所有服务
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service, 
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                //若serviceInterface不是"*" 则创建Zookeeper客户端索取服务列表 并通知(notify)消费者(consumer) 服务可用了
                List<URL> urls = new ArrayList<URL>();
                for (String path : toCategoriesPath(url)) {
                    ////获取类似于http：//xxx.xxx.xxx.xxx/context/com.service.xxxService/consumer的地址
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                        listeners = zkListeners.get(url);
                    }
                    //获取ChildListener
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        listeners.putIfAbsent(listener, new ChildListener() {
                            public void childChanged(String parentPath, List<String> currentChilds) {
                            	ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds));
                            }
                        });
                        zkListener = listeners.get(listener);
                    }
                    //创建Zookeeper客户端
                    zkClient.create(path, false);
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                    	urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                //提醒消费者
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                zkClient.removeChildListener(toUrlPath(url), zkListener);
            }
        }
    }

    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<String>();
            for (String path : toCategoriesPath(url)) {
                try {
                    List<String> children = zkClient.getChildren(path);
                    if (children != null) {
                        providers.addAll(children);
                    }
                } catch (ZkNoNodeException e) {
                    // ignore
                }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }
    
    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }
    
    private String toRootPath() {
        return root;
    }
    
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (Constants.ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categroies;
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categroies = new String[] {Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY, 
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            categroies = url.getParameter(Constants.CATEGORY_KEY, new String[] {Constants.DEFAULT_CATEGORY});
        }
        String[] paths = new String[categroies.length];
        for (int i = 0; i < categroies.length; i ++) {
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categroies[i];
        }
        return paths;
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }
    
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
    	List<URL> urls = new ArrayList<URL>();
        if (providers != null && providers.size() > 0) {
            for (String provider : providers) {
                provider = URL.decode(provider);
                if (provider.contains("://")) {
                    URL url = URL.valueOf(provider);
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (urls == null || urls.isEmpty()) {
        	int i = path.lastIndexOf('/');
        	String category = i < 0 ? path : path.substring(i + 1);
        	URL empty = consumer.setProtocol(Constants.EMPTY_PROTOCOL).addParameter(Constants.CATEGORY_KEY, category);
            urls.add(empty);
        }
        return urls;
    }

    static String appendDefaultPort(String address) {
        if (address != null && address.length() > 0) {
            int i = address.indexOf(':');
            if (i < 0) {
                return address + ":" + DEFAULT_ZOOKEEPER_PORT;
            } else if (Integer.parseInt(address.substring(i + 1)) == 0) {
                return address.substring(0, i + 1) + DEFAULT_ZOOKEEPER_PORT;
            }
        }
        return address;
    }

}