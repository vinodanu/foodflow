import type {Restaurant} from './catalog';
export type Account={username:string;roles:string[]};
export type SavedOrder={id:string;restaurant:string;restaurantId:string;customer:string;partner:string|null;status:string;total:string;version:number;paymentStatus:string;refundStatus:string;createdAt:string};
let csrf:{token:string;headerName:string}|null=null;
export async function api<T>(path:string,method='GET',body?:unknown,key?:string):Promise<T>{
 if(method!=='GET'&&!csrf)csrf=await api('/auth/csrf');
 const headers:Record<string,string>={};if(body)headers['Content-Type']='application/json';if(method!=='GET'&&csrf)headers[csrf.headerName]=csrf.token;if(key)headers['Idempotency-Key']=key;
 const response=await fetch('/api/v1'+path,{method,headers,body:body?JSON.stringify(body):undefined,credentials:'same-origin'});
 if(!response.ok){let problem:{detail?:string}={};try{problem=await response.json();}catch{}throw new Error(problem.detail??(response.status===401?'Please sign in to continue.':response.status===403?'This action is not authorised.':'The request failed. Please retry.'));}
 if(response.status===204)return undefined as T;return response.json();
}
export const loadCatalog=()=>api<Restaurant[]>('/restaurants');
export async function login(username:string,password:string){const account=await api<Account>('/auth/login','POST',{username,password});csrf=null;return account;}
export async function logout(){await api('/auth/logout','POST');csrf=null;}
export const statusStep=(status:string)=>({PLACED:0,ACCEPTED:1,PREPARING:1,READY_FOR_PICKUP:1,PICKED_UP:2,DELIVERED:3,CANCELLED:0,REJECTED:0}[status]??0);
