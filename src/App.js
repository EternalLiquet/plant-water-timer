import React from 'react';
import { Box, Button, Container, createTheme, CssBaseline, Grid, makeStyles, TextField } from '@material-ui/core';
import { ThemeProvider } from '@material-ui/styles';

import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';
import 'firebase/compat/auth';

import { useAuthState } from 'react-firebase-hooks/auth';
import { useCollectionData } from 'react-firebase-hooks/firestore';
import SignIn from './components/SignIn';
import SignInBox from './components/SignInBox';

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});

firebase.initializeApp({
  apiKey: "AIzaSyAtQtSFrmXIylCOktouGw2YsnKwGgv6U3k",
  authDomain: "plant0.firebaseapp.com",
  projectId: "plant0",
  storageBucket: "plant0.appspot.com",
  messagingSenderId: "438322523913",
  appId: "1:438322523913:web:8258be948ab81d86616dd8"
});

const useStyles = makeStyles(() => ({
  mainLayoutGrid: {
    minHeight: "100vh",
    minWidth: "100vw"
  },
  verticalMarginGrid: {
    minHeight: "20vh"
  },
  contentGrid: {
    minHeight: "60vh"
  }
}));

const auth = firebase.auth();
const firestore = firebase.firestore();

function App() {
  const classes = useStyles();
  const [user] = useAuthState(auth);

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg"/>
      <Grid container className={classes.mainLayoutGrid}>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={8} className={classes.contentGrid}><SignInBox user={user} auth={auth} firebase={firebase}/></Grid>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
      </Grid>
    </ThemeProvider>
  );
}

export default App;
