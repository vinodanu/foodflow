import {test,expect,type APIRequestContext} from '@playwright/test';
import {createHmac} from 'node:crypto';
async function signIn(page:any,username:string){await page.getByRole('button',{name:'Account',exact:true}).click();await page.getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Password',{exact:true}).fill('FoodFlow-demo-2026!');await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.getByRole('button',{name:username,exact:true}).first()).toBeVisible();}
async function post(request:APIRequestContext,path:string,body:unknown,key?:string){const csrf=await (await request.get('/api/v1/auth/csrf')).json();return request.post('/api/v1'+path,{data:body,headers:{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})}});}
test('real checkout persists and operations can deliver it',async({page,browser})=>{
 await page.goto('/',{waitUntil:'domcontentloaded'});await expect(page.locator('.restaurant-card')).toHaveCount(6);await signIn(page,'customer');
 await page.getByRole('button',{name:/Healthy$/}).click();await page.getByRole('button',{name:/The Green Bowl/}).click();await page.getByRole('button',{name:'ADD +',exact:true}).first().click();await page.getByRole('button',{name:/View bag/}).click();await expect(page.locator('.grand')).toContainText('284');await page.getByRole('button',{name:'Continue to checkout'}).click();await page.getByLabel('Delivery address').fill('Flat 201, Road 12, Jubilee Hills, Hyderabad 500033');await page.getByRole('button',{name:/Place demo order/}).click();await expect(page.getByRole('heading',{name:'Order received!'})).toBeVisible();
 const saved=(await (await page.request.get('/api/v1/orders')).json())[0];expect(saved.total).toBe('284.00');
 await page.reload();await page.getByRole('button',{name:'My order',exact:true}).click();await expect(page.getByText(new RegExp(saved.id))).toBeVisible();
 const admin=await browser.newContext({baseURL:new URL(page.url()).origin});const adminPage=await admin.newPage();await adminPage.goto('/');expect((await post(admin.request,'/auth/login',{username:'admin',password:'FoodFlow-demo-2026!'})).ok()).toBe(true);await adminPage.reload();await adminPage.getByRole('button',{name:'Operations',exact:true}).click();await expect(adminPage.getByRole('heading',{name:'Order queue'})).toBeVisible();
 for(let i=0;i<5;i++){const current=await (await admin.request.get('/api/v1/orders/'+saved.id)).json();const response=await post(admin.request,'/orders/'+saved.id+'/demo-advance',{status:'DEMO',expectedVersion:current.version,note:'Browser demo test'});expect(response.ok()).toBe(true);}
 await page.getByRole('button',{name:'Refresh order status'}).click();await expect(page.getByRole('heading',{name:'Delivered. Enjoy!'})).toBeVisible();await admin.close();
});
test('API enforces authentication, CSRF, idempotency and ownership',async({page,browser})=>{
 await page.goto('/');expect((await page.request.get('/api/v1/orders')).status()).toBe(401);await signIn(page,'customer');
 const input={restaurantId:'green',address:'Flat 102, Jubilee Hills, Hyderabad 500033',area:'Jubilee Hills, Hyderabad',items:[{dishId:'bowl',quantity:1}]};const key=crypto.randomUUID();
 expect((await page.request.post('/api/v1/orders',{data:input,headers:{'Idempotency-Key':key}})).status()).toBe(403);
 const first=await (await post(page.request,'/orders',input,key)).json();const second=await (await post(page.request,'/orders',input,key)).json();expect(first.id).toBe(second.id);expect((await post(page.request,'/orders',{...input,items:[{dishId:'bowl',quantity:2}]},key)).status()).toBe(409);
 expect((await post(page.request,'/orders/'+first.id+'/demo-advance',{status:'DEMO',expectedVersion:0,note:'Forbidden'})).status()).toBe(403);
 const other=await browser.newContext({baseURL:new URL(page.url()).origin});const p=await other.newPage();await p.goto('/');await signIn(p,'customer2');expect((await other.request.get('/api/v1/orders/'+first.id)).status()).toBe(404);await other.close();
});
test('mobile remains responsive with API catalog',async({page})=>{await page.setViewportSize({width:390,height:844});await page.goto('/');await expect(page.locator('.restaurant-card')).toHaveCount(6);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.getByLabel('Search restaurants or dishes').fill('no-such-dish');await expect(page.getByRole('heading',{name:'No kitchens found'})).toBeVisible();});
test('customer can review order history and cancel before kitchen acceptance',async({page})=>{
 await page.goto('/');await signIn(page,'customer');
 const input={restaurantId:'green',address:'Flat 201, Road 12, Jubilee Hills 500033',area:'Jubilee Hills, Hyderabad',items:[{dishId:'bowl',quantity:1}]};
 const created=await (await post(page.request,'/orders',input,crypto.randomUUID())).json();
 const payment=await (await post(page.request,'/orders/'+created.id+'/payments',{},crypto.randomUUID())).json();
 expect((await post(page.request,'/payments/'+payment.id+'/sandbox-result',{outcome:'SUCCEEDED'})).ok()).toBe(true);
 expect((await page.request.get('/api/v1/orders')).status()).toBe(200);
 const history=await (await page.request.get('/api/v1/orders/'+created.id+'/history')).json();expect(history.some((entry:{action:string})=>entry.action==='OrderPlaced')).toBe(true);
 const paid=await (await page.request.get('/api/v1/orders/'+created.id)).json();
 const cancelled=await post(page.request,'/orders/'+created.id+'/status',{status:'CANCELLED',expectedVersion:paid.version,note:'Customer requested cancellation'});
 expect(cancelled.ok()).toBe(true);const saved=await cancelled.json();expect(saved.status).toBe('CANCELLED');expect(saved.refundStatus).toBe('REFUNDED');
 const finalHistory=await (await page.request.get('/api/v1/orders/'+created.id+'/history')).json();expect(finalHistory.some((entry:{action:string})=>entry.action==='OrderCANCELLED')).toBe(true);
});
test('customer receives payment updates and can contact support from order history',async({page})=>{
 await page.goto('/');await signIn(page,'customer');
 const input={restaurantId:'green',address:'Flat 201, Road 12, Jubilee Hills 500033',area:'Jubilee Hills, Hyderabad',items:[{dishId:'bowl',quantity:1}]};
 const created=await (await post(page.request,'/orders',input,crypto.randomUUID())).json();
 const payment=await (await post(page.request,'/orders/'+created.id+'/payments',{},crypto.randomUUID())).json();
 expect((await post(page.request,'/payments/'+payment.id+'/sandbox-result',{outcome:'SUCCEEDED'})).ok()).toBe(true);
 await page.getByRole('button',{name:'Updates',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Payment succeeded'}).first()).toBeVisible();
 await page.getByRole('button',{name:'Close dialog'}).click();
 await page.getByRole('button',{name:'Orders',exact:true}).click();
 await page.getByRole('button',{name:'View',exact:true}).first().click();
 await page.getByLabel('Subject').fill('Missing item');
 await page.getByLabel('How can we help?').fill('One dish was missing from my order and I would like help.');
 await page.getByRole('button',{name:'Send support request'}).click();
 await expect(page.getByRole('status')).toContainText('Support request submitted.');
 expect((await (await page.request.get('/api/v1/support')).json()).length).toBe(1);
});
test('invalid credentials do not create an authenticated session',async({page})=>{
 await page.goto('/');expect((await page.request.get('/api/v1/health')).status()).toBe(200);const csrf=await (await page.request.get('/api/v1/auth/csrf')).json();
 const response=await page.request.post('/api/v1/auth/login',{data:{username:'customer',password:'incorrect-password'},headers:{[csrf.headerName]:csrf.token}});
 expect(response.status()).toBe(401);expect((await page.request.get('/api/v1/orders')).status()).toBe(401);
});
test('payment webhook verifies signatures and applies duplicate callbacks once',async({page})=>{
 await page.goto('/');await signIn(page,'customer');
 const input={restaurantId:'green',address:'Flat 201, Road 12, Jubilee Hills 500033',area:'Jubilee Hills, Hyderabad',items:[{dishId:'bowl',quantity:1}]};
 const order=await (await post(page.request,'/orders',input,crypto.randomUUID())).json();
 const payment=await (await post(page.request,'/orders/'+order.id+'/payments',{},crypto.randomUUID())).json();
 const body=JSON.stringify({eventId:'browser_event_'+crypto.randomUUID(),paymentId:payment.id,providerReference:payment.providerReference,outcome:'SUCCEEDED'});
 const signature=createHmac('sha256','playwright-local-test-secret').update(body).digest('hex');
 const headers={'Content-Type':'application/json','X-Payment-Signature':signature};
 const applied=await page.request.post('/api/v1/payments/webhook',{data:body,headers});expect(applied.ok()).toBe(true);expect((await applied.json()).duplicate).toBe(false);
 const duplicate=await page.request.post('/api/v1/payments/webhook',{data:body,headers});expect(duplicate.ok()).toBe(true);expect((await duplicate.json()).duplicate).toBe(true);
 expect((await page.request.get('/api/v1/orders/'+order.id)).status()).toBe(200);
 const invalid=await page.request.post('/api/v1/payments/webhook',{data:body,headers:{...headers,'X-Payment-Signature':'bad-signature'}});expect(invalid.status()).toBe(401);
});
test('restaurant operations update menu availability, hours and preparation estimates',async({page})=>{
 await page.goto('/');await signIn(page,'restaurant');
 const csrf=await (await page.request.get('/api/v1/auth/csrf')).json();
 const headers={[csrf.headerName]:csrf.token};
 const menu=await page.request.put('/api/v1/restaurants/green/operations/menu/bowl',{data:{name:'Harvest bowl',price:275,available:false},headers});expect(menu.ok()).toBe(true);
 const catalog=await (await page.request.get('/api/v1/restaurants')).json();
 const dish=catalog.find((restaurant:{id:string})=>restaurant.id==='green').menu.find((item:{id:string})=>item.id==='bowl');
 expect(dish.name).toBe('Harvest bowl');expect(dish.price).toBe(275);expect(dish.available).toBe(false);
 const settings=await page.request.put('/api/v1/restaurants/green/operations',{data:{openForOrders:true,openingHours:{},preparationMinutes:32,acceptanceTimeoutSeconds:300},headers});
 expect(settings.ok()).toBe(true);expect((await settings.json()).preparationMinutes).toBe(32);
});
