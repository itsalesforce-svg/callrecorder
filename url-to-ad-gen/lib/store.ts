import { create } from 'zustand';
import type { AdCopy, AppState, AspectRatio, Product, Step } from './types';

interface Store extends AppState {
  setStep: (step: Step) => void;
  setProduct: (product: Product) => void;
  patchProduct: (patch: Partial<Product>) => void;
  setAd: (ad: AdCopy) => void;
  setAspect: (aspect: AspectRatio) => void;
  setLoading: (isLoading: boolean, message?: string) => void;
  setError: (error: string | null) => void;
  reset: () => void;
}

const initialState: AppState = {
  step: 'url',
  product: null,
  ad: null,
  aspect: '9:16',
  isLoading: false,
  loadingMessage: '',
  error: null,
};

export const useStore = create<Store>((set) => ({
  ...initialState,
  setStep: (step) => set({ step }),
  setProduct: (product) => set({ product }),
  patchProduct: (patch) =>
    set((state) => ({ product: state.product ? { ...state.product, ...patch } : state.product })),
  setAd: (ad) => set({ ad }),
  setAspect: (aspect) => set({ aspect }),
  setLoading: (isLoading, loadingMessage = '') => set({ isLoading, loadingMessage }),
  setError: (error) => set({ error }),
  reset: () => set(initialState),
}));
