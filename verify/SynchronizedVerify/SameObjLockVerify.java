/**
 * 
 */
package com.synchronize.verify;


public class SameObjLockVerify {
	public static void main(String[] args){
		final SameObjLockVerify solv1 = new SameObjLockVerify();
		final SameObjLockVerify solv2 = new SameObjLockVerify();
		
		new Thread(){//��Ϊ�߳�һ
			public void run() { solv1.staticObjLock(); };
		}.start();
		new Thread(){//��Ϊ�̶߳�
			public void run() { solv2.staticObjLock(); };
		}.start();
	}
	
	
	public static Object staticObj = new Object();
	public void objLock(Object obj){
		synchronized (obj) { track("objLock");}
	}
	
	public void staticObjLock(){
		synchronized (SameObjLockVerify.staticObj) { track("staticObjLock");}
	}
	
	
	/**ѭ�����һ������**/
	private static void track(String callerName){
		for(int i = 0;i < 3 ;i++)
			System.out.println(
					callerName+":"+i+"\t|"+
					Thread.currentThread().getName());
			
			Thread.yield();
	}
}
