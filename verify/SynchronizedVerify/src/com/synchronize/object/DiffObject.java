/**
 * 
 */
package com.synchronize.object;

/**
  * a.����������synchronized (this)
  * 1.do1.fun1()+ do2.fun1()��������
  * 2.do1.fun1()+ do2.fun2()��������
  * 
  * b.fun1��synchronized (this)
  * 1.do1.fun1()+ do2.fun1()��������
  * 2.do1.fun1()+ do2.fun2()��������
  * 
  * c.fun1��synchronized (this)��fun2������synchronized
  * 1.do1.fun1()+ do2.fun2()��������
  * 2.do1.fun2()+ do2.fun2()��������
  * 
  * �Ա�a.b��
  * synchronized (this)�Ƕ�������������������ͬ����֮��������ζ����ᷢ�����⣡
 */
public class DiffObject {

	public static void main(String[] args){
		final DiffObject do1 = new DiffObject();
		final DiffObject do2 = new DiffObject();
		new Thread(){
			public void run() {
				do1.fun2();
			}
			
		}.start();
		new Thread(){
			public void run() {
				do2.fun2();
			}
			
		}.start();
	}
	
	public void fun1(){
		System.out.println(Thread.currentThread().getName()+":Start fun1");	
		
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun1 Synchronized!"+i);	
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public synchronized void fun2(){
		System.out.println(Thread.currentThread().getName()+":Start fun2");	
		
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun2 Synchronized!"+i);	
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

	}
}
