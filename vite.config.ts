import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
export default defineConfig({plugins:[react()],build:{target:'es2022'},server:{port:5174,proxy:{'/api':{target:env.FOODFLOW_API_URL??'http://127.0.0.1:8090',changeOrigin:true}}}});
